#!/usr/bin/env bash
# lint-diff.sh — Ring 1, scoped to the change. The repo carries a dirty lint
# baseline (~90 scalafix DisableSyntax errors, ~111 unformatted files), so a
# repo-wide check fails no matter what a change does. This script answers the
# question Ring 1 actually asks: did the CHANGE introduce lint violations?
#
# Usage: lint-diff.sh <baseline-sha> [repo-root]
#        lint-diff.sh --baseline <sha> [repo-root]
# Exit:  0 = no violations in changed files; 1 = violations found / bad input.
#
# METHOD: sbt-scalafix and sbt-scalafmt expose no reliable per-file CHECK task
# (scalafixOnly is per-project and mutates; scalafmtOnly applies). So the
# script runs each repo-wide check ONCE — same commands the capability
# profile declares — and intersects the offending file set with the files
# changed since <baseline-sha> (tracked diff + untracked sources).
#
# RATCHET SEMANTICS: a violation in a file the change touched FAILS even if
# the violation predates the change — touching a file obliges you to lint it.
# Violations confined to untouched files are reported as baseline noise, not
# silently swallowed: their count is printed.
#
# WartRemover is not re-checked here: it is compile-scoped and already gates
# via Ring 0. Compile errors are not lint noise.
#
# No silent fallbacks: an unresolvable baseline or a missing sbt is an error,
# never a pass.
set -uo pipefail

BASELINE=""
ROOT="."
while [ $# -gt 0 ]; do
  case "$1" in
    --baseline)    BASELINE="$2"; shift 2 ;;
    --baseline=*)  BASELINE="${1#*=}"; shift ;;
    -h|--help)     sed -n '2,20p' "$0"; exit 0 ;;
    *)
      if [ -z "$BASELINE" ]; then BASELINE="$1"; else ROOT="$1"; fi
      shift ;;
  esac
done

if [ -z "$BASELINE" ]; then
  echo "lint-diff: missing baseline SHA. Usage: lint-diff.sh <baseline-sha> [repo-root]" >&2
  exit 1
fi
if ! git -C "$ROOT" rev-parse --verify "$BASELINE^{commit}" >/dev/null 2>&1; then
  echo "lint-diff: '$BASELINE' is not a resolvable commit in $ROOT" >&2
  exit 1
fi
if ! command -v sbt >/dev/null 2>&1; then
  echo "lint-diff: sbt not found on PATH — cannot run Ring 1 checks." >&2
  exit 1
fi

cd "$ROOT" || exit 1
ROOT_ABS="$(pwd)"

# Files changed since baseline: tracked edits + untracked sources.
changed="$( { git diff --name-only --diff-filter=ACMR "$BASELINE" -- '*.scala' '*.sbt'
              git ls-files --others --exclude-standard -- '*.scala' '*.sbt'
            } | sort -u )"

if [ -z "$changed" ]; then
  echo "lint-diff: no changed .scala/.sbt files since $BASELINE — nothing to check. OK"
  exit 0
fi
n_changed="$(printf '%s\n' "$changed" | wc -l | tr -d ' ')"
echo "lint-diff: baseline $BASELINE — $n_changed changed .scala/.sbt file(s)"

in_changed() {
  # absolute path -> is it in the changed set?
  local rel="${1#"$ROOT_ABS"/}"
  printf '%s\n' "$changed" | grep -qxF "$rel"
}

fail=0

# ── scalafmt ──────────────────────────────────────────────────────────────
# .scalafmt.conf carries project.git = true: sbt-scalafmt NEVER sees
# untracked files — and every file a spec adds is untracked until the
# Step-12 commit, i.e. exactly the files this gate exists to check. The
# scalafmt CLI is immune to that filter when handed explicit paths, so the
# primary path checks the changed set directly (fast, exact). The sbt
# fallback intersects repo-wide output — sbt-scalafmt emits TWO shapes here
# (plugin versions differ across modules):
#   [warn] scalafmt: /abs/File.scala isn't formatted properly!
#   [warn] scalafmt: /abs/<module>: N files aren't formatted properly:
#   [warn] src/main/scala/Relative.scala     <- resolved against <module>
# and it cannot cover untracked files at all.
echo "lint-diff: scalafmt on the changed set…"
if command -v scalafmt >/dev/null 2>&1; then
  fmt_out="$(printf '%s\n' "$changed" | xargs scalafmt --test -c .scalafmt.conf 2>&1)"
  fmt_rc=$?
  # --test prints a unified diff per unformatted file; report the headers raw.
  fmt_new="$(printf '%s\n' "$fmt_out" | grep -oE '^--- [^ ]+\.(scala|sbt)' \
    | sed 's|^--- ||' | sort -u || true)"
  if [ -n "$fmt_new" ]; then
    echo "scalafmt: NEW violations in changed files:"
    printf '%s\n' "$fmt_new" | while IFS= read -r f; do [ -n "$f" ] && echo "  UNFORMATTED  $f"; done
    fail=1
  fi
  if [ "$fmt_rc" -ne 0 ] && [ -z "$fmt_new" ]; then
    echo "scalafmt: scalafmt --test failed (rc=$fmt_rc) without file output — real error, not lint noise:" >&2
    printf '%s\n' "$fmt_out" | tail -20 >&2
    fail=1
  fi
else
  echo "lint-diff: scalafmt CLI not found — falling back to sbt scalafmtCheckAll intersected with the changed set."
  echo "           NOTE: sbt cannot check untracked files (project.git = true); 'git add' them or install scalafmt."
  fmt_out="$(sbt scalafmtCheckAll 2>&1)"
  fmt_rc=$?
  fmt_bad="$(printf '%s\n' "$fmt_out" | awk '
      /^\[warn\] scalafmt: .*: [0-9]+ files aren'"'"'t formatted properly:$/ {
        line=$0; sub(/^\[warn\] scalafmt: /, "", line); sub(/: [0-9]+ files.*$/, "", line);
        mod=line; next
      }
      /^\[warn\] scalafmt: \/.*\.(scala|sbt) isn'"'"'t formatted/ {
        line=$0; sub(/^\[warn\] scalafmt: /, "", line); sub(/ isn'"'"'t formatted.*$/, "", line);
        print line; next
      }
      mod != "" && /^\[warn\] [^ ]+\.(scala|sbt)/ {
        line=$0; sub(/^\[warn\] /, "", line); sub(/[ \t].*$/, "", line);
        print mod "/" line; next
      }
    ' | sort -u)"
  fmt_new=""
  fmt_base=0
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    if in_changed "$f"; then fmt_new="$fmt_new$f
"; else fmt_base=$((fmt_base + 1)); fi
  done <<FMT
$fmt_bad
FMT
  if [ -n "$fmt_new" ]; then
    echo "scalafmt: NEW violations in changed files:"
    printf '%s\n' "$fmt_new" | while IFS= read -r f; do [ -n "$f" ] && echo "  UNFORMATTED  ${f#"$ROOT_ABS"/}"; done
    fail=1
  fi
  [ "$fmt_base" -gt 0 ] && echo "scalafmt: $fmt_base unformatted file(s) outside the changed set — baseline noise, not failed."
  if [ "$fmt_rc" -ne 0 ] && [ -z "$fmt_bad" ]; then
    echo "scalafmt: sbt scalafmtCheckAll failed (rc=$fmt_rc) without file output — real build error, not lint noise:" >&2
    printf '%s\n' "$fmt_out" | tail -20 >&2
    fail=1
  fi
fi

# ── scalafix ──────────────────────────────────────────────────────────────
echo "lint-diff: running sbt \"scalafixAll --check\" (repo-wide; intersecting)…"
# ThisBuild / semanticdbEnabled := true — no scalafixEnable prelude needed.
fix_out="$(sbt "scalafixAll --check" 2>&1)"
fix_rc=$?
# scalafix violations arrive in TWO formats in this build:
#   [error] /abs/File.scala:LINE:COL: error: [DisableSyntax.x] message
#   [error] -- Error: /abs/File.scala:LINE:COL   <- compiler-reporter style,
#           followed by source/caret lines; message on a later line
# Extract a <path><TAB><line> pair per violation line, keyed on the path.
fix_pairs="$(printf '%s\n' "$fix_out" | awk '
  match($0, /\/[^ :]+\.scala:[0-9]+:[0-9]+/) {
    loc=substr($0, RSTART, RLENGTH)
    path=loc; sub(/:[0-9]+:[0-9]+$/, "", path)
    print path "\t" $0
  }' | sort -u || true)"

fix_new=""
fix_base_files=0
seen_base=""
while IFS=$'\t' read -r fpath line; do
  [ -z "$fpath" ] && continue
  if in_changed "$fpath"; then
    fix_new="$fix_new$line
"
  else
    case " $seen_base " in *" $fpath "*) ;; *) seen_base="$seen_base $fpath"; fix_base_files=$((fix_base_files + 1)) ;; esac
  fi
done <<FIX
$fix_pairs
FIX

if [ -n "$fix_new" ]; then
  echo "scalafix: NEW violations in changed files:"
  printf '%s\n' "$fix_new" | while IFS= read -r l; do [ -n "$l" ] && echo "  ${l#"$ROOT_ABS"/}"; done
  fail=1
fi
[ "$fix_base_files" -gt 0 ] && echo "scalafix: violations in $fix_base_files untouched file(s) — baseline noise, not failed."
if [ "$fix_rc" -ne 0 ] && [ -z "$fix_pairs" ]; then
  echo "scalafix: sbt scalafixAll --check failed (rc=$fix_rc) without file output — real build error, not lint noise:" >&2
  printf '%s\n' "$fix_out" | tail -20 >&2
  fail=1
fi

if [ "$fail" -eq 0 ]; then
  echo "lint-diff: OK — no lint violations in the $n_changed file(s) changed since $BASELINE."
else
  echo "lint-diff: FAILED — violations above are in files this change touched."
  echo "           Fix with: scalafmt on the listed files / sbt scalafixAll, then re-run."
fi
exit "$fail"
