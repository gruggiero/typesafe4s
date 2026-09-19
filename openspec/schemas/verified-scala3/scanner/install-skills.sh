#!/usr/bin/env bash
# install-skills.sh — copy the verified-scala3 schema's skill sources into a
# project's harness skill roots, so agents pick them up.
#
# The schema's skills/ directory is the versioned source of truth; install
# roots are frequently git-ignored (repo-level or user-global), which is
# exactly how skill updates get lost. Run this after adopting the schema and
# after every schema upgrade.
#
# Usage:
#   install-skills.sh [project-root]              -> <root>/.claude/skills
#     PLUS every other harness root that already exists under <root>
#     (.devin/skills, .agents/skills, .pi/skills)
#   install-skills.sh --target <dir> [proj-root]  -> <dir> only
#
# A project may have several harness install roots (.claude/skills,
# .devin/skills, .agents/skills, .pi/skills). An EXISTING root that is never
# re-installed keeps serving stale instructions and nothing notices —
# observed: .devin/skills sat at schema v1 while .claude/skills carried v5,
# and sessions invoked whichever copy the harness listed first. A root that
# does NOT exist cannot mislead anyone, so it is not created here.
#
# spec-lint.sh verifies those PROJECT-LOCAL roots by COMPARING CONTENT against
# this schema's skills/ sources — not by reading the generatedBy stamp. The stamp
# is unreliable as evidence: this schema ports the adk4s workflow under its own
# counter, both lineages write the same "verified-scala3-schema/<N>" prefix, and
# comparing the two counters reported drift between byte-identical files. A copy
# whose instructions match but whose stamp differs is reported as cosmetic.
#
# User-global directories are deliberately NOT checked: one may belong to another
# harness or serve another project on a different schema version, so its contents
# cannot be attributed to this schema.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SKILLS_SRC="$SCRIPT_DIR/../skills"

EXPLICIT_TARGET=""
if [ "${1:-}" = "--target" ]; then
  [ -n "${2:-}" ] || { echo "install-skills: --target needs a directory" >&2; exit 2; }
  EXPLICIT_TARGET="$2"
  shift 2
fi
PROJ_ROOT="${1:-.}"

if [ -n "$EXPLICIT_TARGET" ]; then
  TARGETS=("$EXPLICIT_TARGET")
else
  # Default root is always created; the other harness roots only if they
  # already exist — see the header for why an existing-but-stale root is the
  # hazard and a missing one is not.
  TARGETS=("$PROJ_ROOT/.claude/skills")
  for extra in "$PROJ_ROOT/.devin/skills" "$PROJ_ROOT/.agents/skills" "$PROJ_ROOT/.pi/skills"; do
    [ -d "$extra" ] && TARGETS+=("$extra")
  done
fi

if [ ! -d "$SKILLS_SRC" ]; then
  echo "install-skills: no skills directory at $SKILLS_SRC" >&2
  exit 1
fi

installed=0
retired=0
for TARGET in "${TARGETS[@]}"; do
  mkdir -p "$TARGET"
  for skill_dir in "$SKILLS_SRC"/*/; do
    name="$(basename "$skill_dir")"
    mkdir -p "$TARGET/$name"
    cp "$skill_dir"SKILL.md "$TARGET/$name/SKILL.md"
    echo "installed $name -> $TARGET/$name/SKILL.md"
    installed=$((installed + 1))
  done

  # RETIRED SKILLS — a skill the schema no longer ships keeps being found by
  # the agent if it is left behind in an install root, and stale instructions
  # are indistinguishable from current ones at read time. v5 retired
  # openspec-pseudocode (superseded by openspec-typed-contract, which writes to
  # test sources instead of a directory sbt never compiles). Report leftovers
  # so they can be removed deliberately rather than silently followed.
  for installed_dir in "$TARGET"/*/; do
    [ -d "$installed_dir" ] || continue
    name="$(basename "$installed_dir")"
    # only consider names this schema owns (openspec-* that we ship or shipped)
    case "$name" in openspec-*) ;; *) continue ;; esac
    if [ ! -d "$SKILLS_SRC/$name" ] && [ -n "$(ls -A "$SKILLS_SRC" 2>/dev/null)" ]; then
      case "$name" in
        openspec-pseudocode)
          echo "RETIRED (v5): $name -> superseded by openspec-typed-contract; remove $installed_dir"
          retired=$((retired + 1)) ;;
      esac
    fi
  done
done
[ "$retired" -gt 0 ] && echo "install-skills: $retired retired skill(s) still present — remove them."

echo "install-skills: $installed skill(s) installed across ${#TARGETS[@]} root(s): ${TARGETS[*]}"
echo "NOTE: if an install root is git-ignored in this repo (check 'git check-ignore"
echo "<root>/skills' and your user-global ignore file), the installed copies"
echo "are local-only by design — the schema's skills/ directory remains the"
echo "shared source of truth."
