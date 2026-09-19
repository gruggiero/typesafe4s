// Ring 4 — Stainless formal verification.
//
// `ch.epfl.lara.sbt.stainless.StainlessPlugin` is NOT published to Maven
// Central. It is loaded from the unmanaged jar at `project/lib/sbt-stainless.jar`,
// which — together with the `stainless/` local Maven repository holding the
// Stainless compiler plugin and library — comes from the official release
// bundle `sbt-stainless.zip`:
//
//   https://github.com/epfl-lara/stainless/releases/download/v0.9.9.3/sbt-stainless.zip
//
// Neither is tracked in git (see .gitignore). Run `scripts/setup-stainless.sh`
// to (re)install both.
//
// No other sbt plugins are declared here on purpose: this build must stay a
// leaf. Nothing from the root build's project/plugins.sbt applies.
