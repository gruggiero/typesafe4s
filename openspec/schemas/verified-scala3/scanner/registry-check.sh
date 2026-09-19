#!/usr/bin/env bash
# registry-check.sh — verify that every symbol referenced in the concept
# registry's Implementation maps still exists in the source tree.
#
# The Implementation map is the registry's single anti-drift mechanism: specs
# reference behavior (Concept/action) and the map binds behavior to code. A
# stale row silently breaks that binding, so this check belongs in CI next to
# format/lint checks.
#
# Usage: registry-check.sh [repo-root] [--except-change NAME ...]
#                                   [--only-change NAME]
# Exit:  0 = all rows verified; 1 = stale rows found (listed on stdout).
#
# CHANGE SCOPING: the spec-reference (pass 3) and EARS (pass 4) checks scan
# every in-flight change under openspec/changes/. When several changes are
# in flight, an unrelated change's rows fail this check and the failure
# reads as the ACTIVE change's problem. Scope the per-change passes:
#   --only-change NAME    check change specs only under changes/NAME
#   --except-change NAME  skip changes/NAME (repeatable)
# Neither flag touches the Implementation-map, fold-field, finding, or
# main-spec (openspec/specs/) checks — those always run. Exclusions are
# printed, never silent.
#
# Three passes:
#
# 1. SYMBOLS — per backtick-quoted token in an "## Implementation map" or
#    "## Synchronizations" impl: line (first table cell — the Element label —
#    is skipped: it holds concept vocabulary, not code):
#   - Concept/action references (leading name matches a concept file) -> skip
#   - contains an ellipsis                            -> skip (runtime path)
#   - starts with specs/ or openspec/                 -> must be an openspec dir/file
#   - looks like a file path (contains "/" and ".")   -> must match a tracked file
#   - kebab-case (contains "-")                       -> full-token fixed-string grep
#   - otherwise: every identifier of length >= 6 in the token must appear
#     somewhere in the tracked, non-markdown source tree
#    FILE-SCOPED BINDING: when the SAME row also cites a resolvable file
#    path, that row's symbols are additionally checked INSIDE that file. A
#    scoped miss that still exists tree-wide is reported as WEAK — a warning,
#    not a failure, because rows legitimately mix a file citation with
#    symbols living elsewhere (e.g. an algebra file plus its implementations)
#    — but a WEAK row is a candidate for tightening (cite the symbol's own
#    file). A symbol found NOWHERE is STALE (failure) as before.
#    Bare filename tokens with a known source extension (e.g. `foo.smithy`)
#    are checked as tracked files, not decomposed into identifiers.
#
# 2. FOLD FIELDS — an Implementation-map cell may declare the exact fields a
#    state fold populates, using the convention "maps field1, field2, ...".
#    Every named field must appear in the file referenced (backticked path)
#    in the same cell. This catches the declared-but-never-folded class of
#    bug (a field present on the model shape that no fold writes): listing a
#    field here asserts the fold actually maps it.
#
# 3. SPEC REFERENCES — every concept cited in a change spec's
#    "## Concepts Used (behavioral)" table (openspec/changes/*/specs/**, the
#    archive excluded) must be declared by a registry file, and a cited
#    `Concept/action` must name an action that appears in that concept
#    file's `actions` block (between the `actions` and `operational
#    principle` lines of its concept specification) — a mention elsewhere in
#    the file (prose, deviations) does not count as a declared action.
#    NEW-CONCEPT rows: a row whose concept cell carries a "(NEW ...)" /
#    "(created by ...)" annotation declares a concept this change CREATES;
#    it is skipped here (the registry file lands with the implementation,
#    apply Step 12) — once created, the citation verifies normally.
set -euo pipefail

ROOT="."
EXCEPT_CHANGES=""
ONLY_CHANGE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --except-change)    EXCEPT_CHANGES="$EXCEPT_CHANGES $2"; shift 2 ;;
    --except-change=*)  EXCEPT_CHANGES="$EXCEPT_CHANGES ${1#*=}"; shift ;;
    --only-change)      ONLY_CHANGE="$2"; shift 2 ;;
    --only-change=*)    ONLY_CHANGE="${1#*=}"; shift ;;
    -h|--help)
      sed -n '2,22p' "$0"; exit 0 ;;
    *)                  ROOT="$1"; shift ;;
  esac
done
CONCEPTS_DIR="$ROOT/openspec/concepts"

if [ ! -d "$CONCEPTS_DIR" ]; then
  echo "registry-check: no $CONCEPTS_DIR directory — nothing to verify."
  exit 0
fi

# Change-dir name for a spec path under openspec/changes/, "" for main specs.
change_of() {
  case "$1" in
    */openspec/changes/*/specs/*)
      local rest="${1#*/openspec/changes/}"
      printf '%s' "${rest%%/*}"
      ;;
    *) printf '' ;;
  esac
}

# Should this spec file be skipped by the per-change passes?
spec_excluded() {
  local c
  c="$(change_of "$1")"
  [ -z "$c" ] && return 1   # main spec (openspec/specs/) — always checked
  if [ -n "$ONLY_CHANGE" ] && [ "$c" != "$ONLY_CHANGE" ]; then return 0; fi
  case " $EXCEPT_CHANGES " in
    *" $c "*) return 0 ;;
    *)        return 1 ;;
  esac
}

if [ -n "$ONLY_CHANGE" ]; then
  echo "registry-check: spec/EARS passes restricted to change '$ONLY_CHANGE'"
fi
if [ -n "$EXCEPT_CHANGES" ]; then
  echo "registry-check: excluding change(s):$EXCEPT_CHANGES"
fi

fail=0
checked=0
weak=0

# Concept names — from file names AND from concept declarations inside the
# files ("# Concept: X" / "## Concept: X" headings and "concept X" spec
# lines), so multi-concept files (e.g. bootstrap.md) are covered. Two forms
# are kept: lowercased-dashless (for symbol-pass skipping) and exact names
# mapped to their file (for the spec-reference pass).
concept_names=""
concept_index=""   # lines of "ExactName<TAB>file.md"
for cf in "$CONCEPTS_DIR"/*.md; do
  [ -e "$cf" ] || continue
  cbase="$(basename "$cf")"
  [ "$cbase" = "README.md" ] && continue
  concept_names="$concept_names $(basename "$cf" .md | tr -d '-' | tr '[:upper:]' '[:lower:]')"
  declared="$(grep -ohE '^#+ Concept: [A-Za-z]+|^concept [A-Za-z]+' "$cf" \
    | grep -oE '[A-Za-z]+$' | sort -u || true)"
  while IFS= read -r cname; do
    [ -z "$cname" ] && continue
    concept_names="$concept_names $(printf '%s' "$cname" | tr '[:upper:]' '[:lower:]')"
    concept_index="$concept_index$cname	$cbase
"
  done <<EOF
$declared
EOF
done

is_concept_ref() {
  # matches "Name" or "Name/action" where Name is a known concept
  local head
  head="$(printf '%s' "$1" | cut -d/ -f1 | tr -d '-' | tr '[:upper:]' '[:lower:]')"
  case " $concept_names " in
    *" $head "*) return 0 ;;
    *)           return 1 ;;
  esac
}

# grep the tracked source tree (everything except openspec/ and markdown)
in_sources() {
  git -C "$ROOT" grep -q -F -- "$1" -- ':!openspec' ':!*.md' 2>/dev/null
}

file_exists() {
  [ -n "$(git -C "$ROOT" ls-files -- "*$1" 2>/dev/null)" ]
}

tracked_path() {
  git -C "$ROOT" ls-files -- "*$1" 2>/dev/null | head -1
}

in_file() {
  grep -q -F -- "$1" "$ROOT/$2" 2>/dev/null
}

# an action is declared only inside an `actions` block of a concept
# specification (between `actions` and `operational principle` / fence end)
action_declared() {
  # Same SIGPIPE-under-pipefail hazard as spec-lint's W8: `awk | grep -q` can report a declared action
  # as missing when grep exits before awk finishes writing. Materialise first, then match without a pipe.
  local block
  block="$(awk '/^actions[ \t]*$/{a=1;next} /^operational principle/{a=0} /^```/{a=0} a' "$1")"
  grep -q -w -- "$2" <<<"$block"
}

for f in "$CONCEPTS_DIR"/*.md; do
  [ -e "$f" ] || continue
  base="$(basename "$f")"
  [ "$base" = "README.md" ] && continue

  # Rows of Implementation map tables (Element cell stripped — it holds
  # concept vocabulary, not code) + "impl:" lines of sync sections.
  lines="$(awk '
    /^## Implementation map/ {m=1; next}
    /^## /                   {m=0}
    m && /^\|/               {sub(/^\|[^|]*\|/, "|"); print}
    /^impl:/                 {print}
  ' "$f")"

  # Process ROW BY ROW so symbols can be bound to a file cited on the same
  # row (FILE-SCOPED BINDING — see header).
  while IFS= read -r row; do
    [ -z "$row" ] && continue
    # shellcheck disable=SC2016  # single quotes are intentional: this is a grep
    # PATTERN containing backticks, not a string meant to expand.
    row_tokens="$(printf '%s\n' "$row" | grep -o '`[^`]*`' | tr -d '`' || true)"
    [ -z "$row_tokens" ] && continue

    # scope: first resolvable source-file path cited on this row
    scope=""
    while IFS= read -r t; do
      case "$t" in
        specs/*|openspec/*) continue ;;
        */*.*)
          scope="$(tracked_path "$t")"
          [ -n "$scope" ] && break
          ;;
      esac
    done <<ROWT
$row_tokens
ROWT

    while IFS= read -r token; do
      [ -z "$token" ] && continue

      # registry vocabulary and runtime paths are not code symbols
      if is_concept_ref "$token"; then continue; fi
      case "$token" in
        *…*|*"..."*) continue ;;
        specs/*|openspec/*)
          checked=$((checked + 1))
          if [ ! -e "$ROOT/openspec/${token#openspec/}" ] && [ ! -e "$ROOT/$token" ] \
             && [ ! -e "$ROOT/openspec/$token" ]; then
            echo "STALE  $base: openspec reference not found: $token"
            fail=1
          fi
          continue
          ;;
      esac

      checked=$((checked + 1))

      case "$token" in
        */*.*)
          if ! file_exists "$token"; then
            echo "STALE  $base: file not found: $token"
            fail=1
          fi
          continue
          ;;
        *.scala|*.smithy|*.proto|*.sbt|*.conf|*.yml|*.yaml|*.json|*.sql|*.sh|*.java|*.py)
          # bare filename with a known source extension → tracked-file check
          if ! file_exists "$token"; then
            echo "STALE  $base: file not found: $token"
            fail=1
          fi
          continue
          ;;
      esac

      if printf '%s' "$token" | grep -q -- '-'; then
        if ! in_sources "$token"; then
          echo "STALE  $base: token not found: $token"
          fail=1
        elif [ -n "$scope" ] && ! in_file "$token" "$scope"; then
          echo "WEAK   $base: token '$token' not in cited file $scope (exists elsewhere — consider citing its own file)"
          weak=$((weak + 1))
        fi
        continue
      fi

      # every identifier of length >= 6 must exist — inside the row's cited
      # file when one is present, otherwise anywhere in the source tree
      idents="$(printf '%s\n' "$token" | grep -oE '[A-Za-z_][A-Za-z0-9_]{5,}' | sort -u || true)"
      while IFS= read -r ident; do
        [ -z "$ident" ] && continue
        if ! in_sources "$ident"; then
          echo "STALE  $base: identifier not found: $ident   (from: $token)"
          fail=1
        elif [ -n "$scope" ] && ! in_file "$ident" "$scope"; then
          echo "WEAK   $base: identifier '$ident' not in cited file $scope (exists elsewhere — consider citing its own file)   (from: $token)"
          weak=$((weak + 1))
        fi
      done <<EOF2
$idents
EOF2
    done <<ROWT2
$row_tokens
ROWT2
  done <<EOF
$lines
EOF

  # ── Pass 2: fold fields — "maps f1, f2, ..." convention ──────────────────
  fold_rows="$(printf '%s\n' "$lines" | grep -- 'maps [A-Za-z_]' || true)"
  while IFS= read -r row; do
    [ -z "$row" ] && continue
    # shellcheck disable=SC2016  # single quotes are intentional: this is a grep
    # PATTERN containing backticks, not a string meant to expand.
    fold_file="$(printf '%s' "$row" | grep -o '`[^`]*/[^`]*\.[a-z]*`' | head -1 | tr -d '\`')"
    if [ -z "$fold_file" ] || ! file_exists "$fold_file"; then
      echo "STALE  $base: fold row declares mapped fields but names no resolvable file: $row"
      fail=1
      continue
    fi
    tracked="$(git -C "$ROOT" ls-files -- "*$fold_file" | head -1)"
    fields="$(printf '%s' "$row" | sed 's/.*maps //' | tr '|' ' ' \
      | grep -oE '[A-Za-z_][A-Za-z0-9_]*' || true)"
    while IFS= read -r field; do
      [ -z "$field" ] && continue
      checked=$((checked + 1))
      if ! grep -q -w -- "$field" "$ROOT/$tracked"; then
        echo "STALE  $base: fold field '$field' not found in $fold_file (declared mapped, but the fold does not mention it)"
        fail=1
      fi
    done <<EOF4
$fields
EOF4
  done <<EOF3
$fold_rows
EOF3
done

# ── Pass 3: spec references — Concepts Used (behavioral) tables ────────────
spec_refs=0
for spec in "$ROOT"/openspec/changes/*/specs/*/spec.md; do
  [ -e "$spec" ] || continue
  case "$spec" in */archive/*) continue ;; esac
  spec_excluded "$spec" && continue
  sbase="${spec#"$ROOT"/openspec/changes/}"

  # first table cell of each row in the behavioral concepts table
  # The section can hold more than one table (a "Synchronizations exercised"
  # subsection is common), so header rows must be dropped generically: a
  # header is exactly the row followed by the |---|---| separator. Buffer one
  # row and discard it when a separator arrives.
  cells="$(awk -F'|' '
    /^## Concepts Used \(behavioral\)/ {m=1; next}
    /^## /                             {m=0}
    m && /^\|/ {
      if ($0 ~ /^\|[ \t:-]*-/) { prev = ""; next }   # separator: prev was a header
      if (prev != "") print prev
      prev = $2
      next
    }
    m { if (prev != "") { print prev; prev = "" } }
    END { if (prev != "") print prev }
  ' "$spec")"
  # refs come backticked (`Concept` / `Concept/action`) or as markdown
  # links ([Concept](path/to/concept.md)) — both count
  eligible="$(printf '%s\n' "$cells" | grep -viE '\((new|created by)' || true)"
  # ...or bare, which is just as common in practice. A parser that accepted
  # only the first two forms read a whole change as zero references and
  # reported OK — the silent-empty-scan failure, again.
  # shellcheck disable=SC2016  # single quotes are intentional: this is a grep
  # PATTERN containing backticks, not a string meant to expand.
  refs="$( { printf '%s\n' "$eligible" | grep -o '`[^`]*`' | tr -d '\`';
             printf '%s\n' "$eligible" | grep -oE '\[[A-Za-z][A-Za-z0-9/]*\]' | tr -d '[]';
             printf '%s\n' "$eligible" | sed 's/^[ \t]*//; s/[ \t]*$//' \
               | grep -E '^[A-Z][A-Za-z0-9]*(/[A-Za-z][A-Za-z0-9]*)?$' \
               | grep -vx 'Concept'; } \
           | sort -u || true)"
  # a table with data rows that yields no reference is a parse failure, not a
  # clean bill of health
  rows="$(printf '%s\n' "$eligible" | grep -cE '[A-Za-z]' || true)"
  if [ "$rows" -gt 1 ] && [ -z "$refs" ]; then
    echo "SPEC   $sbase: has a Concepts Used (behavioral) table with $rows row(s) but NO reference parsed — check the table shape"
    fail=1
  fi

  while IFS= read -r ref; do
    [ -z "$ref" ] && continue
    case "$ref" in [A-Z]*) ;; *) continue ;; esac
    spec_refs=$((spec_refs + 1))
    cname="${ref%%/*}"
    action=""
    case "$ref" in */*) action="${ref#*/}" ;; esac
    cfile="$(printf '%s' "$concept_index" | awk -F'\t' -v n="$cname" '$1==n{print $2; exit}')"
    if [ -z "$cfile" ]; then
      echo "SPEC   $sbase: cites unknown concept '$cname' — no registry file declares it"
      fail=1
      continue
    fi
    if [ -n "$action" ] && ! action_declared "$CONCEPTS_DIR/$cfile" "$action"; then
      if grep -q -w -- "$action" "$CONCEPTS_DIR/$cfile"; then
        echo "SPEC   $sbase: cites action '$ref' but '$action' is not in the actions block of $cfile (only mentioned elsewhere in the file)"
      else
        echo "SPEC   $sbase: cites action '$ref' but '$action' does not appear in $cfile"
      fi
      fail=1
    fi
  done <<EOF5
$refs
EOF5
done

# ── Pass 4: Ring 5 EARS references and open findings ─────────────────────
# PROJECT-LOCAL PASS (typesafe4s, schema v5). Not present upstream in
# adk4s: this project runs Ring 5 as trace-based verification with its own
# observability/ring5 model and an openspec/ring5-findings/ directory, and
# this pass is what keeps those two honest — every EARS id declared in a spec
# must have a test back-reference, and every open finding must carry an
# unchecked disposition and a linked artifact that EXISTS.
#
# Preserved verbatim through the v5 merge onto the upstream parser fixes
# (bare-token Implementation-map cells, generic table-header dropping,
# declared fold-field verification). Do not drop it when next syncing
# upstream — diff Pass 1-3 only.
ears_refs=0
while IFS= read -r spec; do
  case "$spec" in */archive/*) continue ;; esac
  spec_excluded "$spec" && continue
  spec_name="$(basename "$(dirname "$spec")")"
  ears_ids="$(awk '
    /^## Temporal Properties \(Ring 5\)/ { temporal=1; next }
    temporal && /^## / { temporal=0 }
    temporal && /^### EARS-[A-Za-z0-9-]+:/ {
      id=$0
      sub(/^### /, "", id)
      sub(/:.*/, "", id)
      print id
    }
  ' "$spec")"

  while IFS= read -r ears_id; do
    [ -z "$ears_id" ] && continue
    ears_refs=$((ears_refs + 1))
    back_reference="// spec: $spec_name — $ears_id"
    if ! git -C "$ROOT" grep -q -F -- "$back_reference" -- ':(glob)**/src/test/**/*.scala' 2>/dev/null; then
      echo "EARS   ${spec#"$ROOT"/}: missing test back-reference '$back_reference'"
      fail=1
    fi
  done <<EOF6
$ears_ids
EOF6
done < <(find "$ROOT/openspec/specs" "$ROOT/openspec/changes" -path '*/specs/*/spec.md' -type f 2>/dev/null)

findings_dir="$ROOT/openspec/ring5-findings"
if [ -d "$findings_dir" ]; then
  while IFS= read -r finding; do
    status="$(grep -m1 '^Status:' "$finding" | sed 's/^Status:[[:space:]]*//')"
    if [ "$status" = "open" ]; then
      if ! grep -qE '^- \[ \] ' "$finding"; then
        echo "FINDING $(basename "$finding"): open finding has no unchecked disposition item"
        fail=1
      fi
      linked_artifacts="$(grep -oE 'Linked artifact: `((openspec/changes/[^`]+)|([^`]*src/test/[^`]*\.scala))`' "$finding" | sed 's/^Linked artifact: `//; s/`$//' || true)"
      linked_artifact_exists=0
      while IFS= read -r artifact; do
        [ -z "$artifact" ] && continue
        if [ -e "$ROOT/$artifact" ]; then
          linked_artifact_exists=1
        fi
      done <<EOF7
$linked_artifacts
EOF7
      if [ "$linked_artifact_exists" -ne 1 ]; then
        echo "FINDING $(basename "$finding"): open finding has no linked existing change or test path"
        fail=1
      fi
    fi
    if grep -qi 'scenario authored from observed' "$finding"; then
      echo "FINDING $(basename "$finding"): direction-of-truth violation — scenario authored from observed behaviour"
      fail=1
    fi
  done < <(find "$findings_dir" -maxdepth 1 -type f -name 'F-*.md' -print)
fi

if [ "$fail" -eq 0 ]; then
  echo "registry-check: OK ($checked implementation-map tokens verified, $spec_refs spec concept references checked, $ears_refs EARS references checked, $weak weak binding(s) to tighten)"
else
  echo ""
  echo "registry-check: FAILED — STALE rows mean an Implementation map (or a"
  echo "declared fold field) no longer matches the code; SPEC rows mean a"
  echo "change spec cites a concept or action the registry does not declare."
  echo "WEAK rows (warnings) mean a symbol is not in the file its row cites —"
  echo "tighten the row when convenient. Fix the concept file (or the spec)"
  echo "as part of the change."
fi
exit "$fail"
