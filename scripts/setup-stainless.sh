#!/usr/bin/env bash
# Install the Stainless toolchain used by Ring 4 of the verified-scala3
# workflow into verified/.
#
# What it installs (none of it is tracked in git — see .gitignore):
#   verified/project/lib/sbt-stainless.jar   the sbt plugin (not on Maven Central)
#   verified/stainless/                      local Maven repo: Stainless compiler
#                                            plugin (~83 MB) + library sources
#
# Both come from the official epfl-lara release bundle. The ScalaZ3 jar that
# enables the native Z3 solver is NOT part of that bundle and is not installed
# here; without it Stainless falls back to smt-z3 (needs a `z3` binary on
# PATH). See verified/unmanaged/README.md.
#
# Usage: scripts/setup-stainless.sh [--force]
set -euo pipefail

STAINLESS_VERSION="0.9.9.3"
BUNDLE_URL="https://github.com/epfl-lara/stainless/releases/download/v${STAINLESS_VERSION}/sbt-stainless.zip"
BUNDLE_SHA256="4396ddbb74dabe90151f22fa9b74c81981fbb54d367ee279020edcd104426609"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERIFIED_DIR="$REPO_ROOT/verified"
PLUGIN_JAR="$VERIFIED_DIR/stainless/ch/epfl/lara/stainless-dotty-plugin_3.7.2/${STAINLESS_VERSION}/stainless-dotty-plugin_3.7.2-${STAINLESS_VERSION}.jar"
SBT_PLUGIN_JAR="$VERIFIED_DIR/project/lib/sbt-stainless.jar"

FORCE=0
[[ "${1:-}" == "--force" ]] && FORCE=1

if [[ $FORCE -eq 0 && -f "$PLUGIN_JAR" && -f "$SBT_PLUGIN_JAR" ]]; then
  echo "Stainless ${STAINLESS_VERSION} already installed under verified/. Use --force to reinstall."
  exit 0
fi

for tool in curl unzip sha256sum; do
  command -v "$tool" >/dev/null || { echo "error: $tool is required" >&2; exit 1; }
done

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "Downloading sbt-stainless.zip (~82 MB) from $BUNDLE_URL"
curl -fL --retry 3 -o "$TMP_DIR/sbt-stainless.zip" "$BUNDLE_URL"

echo "Verifying checksum"
ACTUAL_SHA256="$(sha256sum "$TMP_DIR/sbt-stainless.zip" | cut -d' ' -f1)"
if [[ "$ACTUAL_SHA256" != "$BUNDLE_SHA256" ]]; then
  echo "error: checksum mismatch for sbt-stainless.zip" >&2
  echo "  expected $BUNDLE_SHA256" >&2
  echo "  actual   $ACTUAL_SHA256" >&2
  exit 1
fi

echo "Unpacking into verified/"
rm -rf "$VERIFIED_DIR/stainless" "$SBT_PLUGIN_JAR"
unzip -q -o "$TMP_DIR/sbt-stainless.zip" -d "$VERIFIED_DIR"

# The ScalaZ3-merged copy of the compiler plugin is derived from the jar we
# just replaced — drop it so the next Ring 4 run rebuilds it.
rm -rf "$VERIFIED_DIR/.ring4/plugin"

echo
echo "Installed Stainless ${STAINLESS_VERSION}:"
echo "  $SBT_PLUGIN_JAR"
echo "  $PLUGIN_JAR"
if [[ -f "$VERIFIED_DIR/unmanaged/scalaz3_3-4.13.4.jar" ]]; then
  echo "  native Z3: verified/unmanaged/scalaz3_3-4.13.4.jar present"
else
  echo "  native Z3: MISSING (verified/unmanaged/scalaz3_3-4.13.4.jar)"
  echo "             Ring 4 will fall back to smt-z3, which needs a z3 binary"
  echo "             on PATH. See verified/unmanaged/README.md."
fi
echo
echo "Run Ring 4 with: scripts/ring4.sh"
