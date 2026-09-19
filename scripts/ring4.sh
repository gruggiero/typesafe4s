#!/usr/bin/env bash
# Ring 4 — run Stainless formal verification over the mirror kernels in
# verified/ and report the result in a form that can be pasted into a
# checkpoint or an evidence-ledger entry.
#
# Usage: scripts/ring4.sh [extra sbt args...]
#   RING4_TIMEOUT  seconds before the run is killed (default 1800). Stainless
#                  0.9.9.3 has no working per-VC timeout: a hard VC hangs
#                  forever, so the wall-clock guard is the only backstop.
#
# Exit codes: 0 all VCs valid · 1 verification failed, hung or is not installed
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERIFIED_DIR="$REPO_ROOT/verified"
LOG_DIR="$VERIFIED_DIR/.ring4"
LOG_FILE="$LOG_DIR/last-run.log"
TIMEOUT="${RING4_TIMEOUT:-1800}"

if [[ ! -f "$VERIFIED_DIR/project/lib/sbt-stainless.jar" ]]; then
  echo "Ring 4: Stainless is not installed. Run scripts/setup-stainless.sh first." >&2
  exit 1
fi

mkdir -p "$LOG_DIR"
cd "$VERIFIED_DIR"

echo "Ring 4: verifying $(find src/main/scala -name '*.scala' | wc -l) kernel file(s) — log: $LOG_FILE"
timeout --signal=INT "$TIMEOUT" sbt -batch ring4 "$@" 2>&1 | tee "$LOG_FILE"
SBT_STATUS=${PIPESTATUS[0]}

if [[ $SBT_STATUS -eq 124 ]]; then
  echo
  echo "Ring 4: FAILED — killed after ${TIMEOUT}s. A verification condition is almost" >&2
  echo "certainly hanging; see verified/README.md ('When verification hangs')." >&2
  echo "Last progress line: $(grep -o 'Verified: [0-9]* / [0-9]*' "$LOG_FILE" | tail -1)" >&2
  exit 1
fi

SUMMARY="$(grep -o 'total: *[0-9].*time: *[0-9.]*' "$LOG_FILE" | tail -1)"
if [[ -z "$SUMMARY" ]]; then
  echo
  echo "Ring 4: FAILED — Stainless produced no verification summary (sbt exit $SBT_STATUS)." >&2
  echo "The kernels probably did not compile; see $LOG_FILE." >&2
  exit 1
fi

SOLVER="smt-z3"
grep -q 'nativez3' "$LOG_FILE" && SOLVER="nativez3"
INVALID="$(sed -n 's/.*invalid: *\([0-9]*\).*/\1/p' <<<"$SUMMARY" | tail -1)"
UNKNOWN="$(sed -n 's/.*unknown: *\([0-9]*\).*/\1/p' <<<"$SUMMARY" | tail -1)"

echo
echo "Ring 4 summary: $SUMMARY"
echo "Ring 4 solver:  $SOLVER"
[[ "$SOLVER" == "smt-z3" ]] &&
  echo "Ring 4: WARNING — native Z3 is not in use; see verified/unmanaged/README.md." >&2

if [[ "${INVALID:-1}" != "0" || "${UNKNOWN:-1}" != "0" || $SBT_STATUS -ne 0 ]]; then
  echo "Ring 4: FAILED (invalid: ${INVALID:-?}, unknown: ${UNKNOWN:-?}, sbt exit $SBT_STATUS)" >&2
  echo "Fix the KERNEL or the IMPLEMENTATION it mirrors — never weaken the contract." >&2
  exit 1
fi

echo "Ring 4: PASSED — all verification conditions valid."
