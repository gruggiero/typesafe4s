// ─────────────────────────────────────────────────────────────────────────────
// Ring 4 — Stainless formal verification (verified-scala3 schema)
// ─────────────────────────────────────────────────────────────────────────────
//
// This is a SEPARATE, SELF-CONTAINED sbt build, deliberately NOT a subproject
// of the root build. Three reasons:
//
//  1. Stainless 0.9.9.3 pins its frontend to Scala 3.7.2; the rest of the
//     repository is on 3.9.0. TASTy is only backward compatible, so a verified
//     module can never depend on production modules anyway — it is a LEAF by
//     construction, which is exactly what the mirror-module pattern requires.
//  2. The root build is an sbt-projectmatrix build whose root project
//     aggregates every generated cell. A `verified` subproject would be
//     compiled by every `sbt compile` and every CI run, pulling a second Scala
//     compiler and the 83 MB Stainless plugin into ordinary builds — which
//     would fail wherever the (untracked) Stainless artifacts are absent.
//  3. Nothing from the root build leaks in: no `-Werror`, no `-Wunused`, no
//     kyo-compat plugin, no matrix. Kernels are pure models and must not be
//     held to the production build's warning policy.
//
// WHAT TO MIRROR HERE. Ring 4 earns its keep on pure, algorithmic invariants.
// In this SDK that means the retry schedule (growth, ceiling, jitter, budget)
// and the token-budget division — both are arithmetic over values, with no
// effect and no backend in sight. It does NOT mean anything touching the
// carrier, the transport or a facade: those are Ring 5's business.
//
// Run it with:   scripts/ring4.sh          (from the repository root)
//            or: cd verified && sbt ring4
//
// Setup (once):  scripts/setup-stainless.sh
// Playbook:      verified/README.md

lazy val scalaVerified     = "3.7.2"        // Stainless 0.9.9.3 frontend pin (root build is on 3.9.0)
lazy val scalaZ3Version    = "4.13.4"       // native Z3 via the ScalaZ3 wrapper
lazy val scalaZ3JarName    = s"scalaz3_3-$scalaZ3Version.jar"

/** Merge the ScalaZ3 jar into the Stainless compiler-plugin jar.
  *
  * Stainless reaches Z3 through the ScalaZ3 JNI wrapper (`z3.Z3Wrapper`), and
  * Inox probes for it with `SolverFactory.hasNativeZ3`. The compiler plugin
  * runs in a classloader that only searches its OWN jar, so having ScalaZ3 on
  * the compile classpath is not enough — the classes have to live inside the
  * plugin jar. Without the merge Stainless silently falls back to `smt-z3`
  * (Z3 over SMT-LIB on stdin), which is slower and times out on harder VCs.
  *
  * Idempotent: if the merged jar already contains `z3/Z3Wrapper.class` it is
  * reused. Returns the jar that should be passed to `-Xplugin:`; on any
  * problem it returns the original plugin jar, so verification still runs
  * (on `smt-z3`) instead of failing the build.
  */
def mergeScalaZ3IntoPlugin(pluginJar: File, scalaZ3Jar: File, outDir: File, log: Logger): File =
  if (!pluginJar.exists) pluginJar
  else if (!scalaZ3Jar.exists) {
    log.warn(s"Ring 4: $scalaZ3Jar not found — Stainless will fall back to smt-z3 " +
             "(requires a z3 binary on PATH). See verified/unmanaged/README.md.")
    pluginJar
  } else {
    // Deliberately NOT next to the plugin jar: that lives inside the
    // `stainless/` local repository, which setup-stainless.sh replaces
    // wholesale. `outDir` also survives `clean`, so the ~90 MB repack happens
    // once per toolchain install rather than once per Ring 4 run.
    IO.createDirectory(outDir)
    val outJar = outDir / (pluginJar.getName.stripSuffix(".jar") + "-merged.jar")
    val alreadyMerged = outJar.exists && {
      val jf = new java.util.jar.JarFile(outJar)
      try jf.getEntry("z3/Z3Wrapper.class") != null
      finally jf.close()
    }
    if (alreadyMerged) outJar
    else {
      log.info(s"Ring 4: merging ScalaZ3 into the Stainless plugin jar -> $outJar")
      val tmpDir = java.nio.file.Files.createTempDirectory("stainless-scalaz3-merge")
      try {
        def unjar(jar: File): Int =
          new java.lang.ProcessBuilder("jar", "xf", jar.getAbsolutePath)
            .directory(tmpDir.toFile).inheritIO().start().waitFor()
        val rc =
          unjar(pluginJar) + unjar(scalaZ3Jar) +
            // 0 compression: the merged jar is a local build artifact, and
            // packing ~90 MB uncompressed is far faster than deflating it.
            new java.lang.ProcessBuilder(
              "jar", "cf0", outJar.getAbsolutePath, "-C", tmpDir.toFile.getAbsolutePath, "."
            ).inheritIO().start().waitFor()
        if (rc == 0) outJar
        else {
          log.warn(s"Ring 4: jar merge failed (exit $rc) — falling back to smt-z3")
          IO.delete(outJar)
          pluginJar
        }
      } catch {
        // e.g. no `jar` on PATH. Degrading to smt-z3 beats failing the build.
        case scala.util.control.NonFatal(e) =>
          log.warn(s"Ring 4: could not merge ScalaZ3 ($e) — falling back to smt-z3")
          IO.delete(outJar)
          pluginJar
      } finally
        java.nio.file.Files.walk(tmpDir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => java.nio.file.Files.delete(p))
    }
  }

lazy val verified = (project in file("."))
  .enablePlugins(StainlessPlugin)
  .settings(
    name         := "typesafe4s-verified",
    organization := "io.github.gruggiero",
    version      := "0.1.0-SNAPSHOT",
    scalaVersion := scalaVerified,

    // The Stainless plugin jar and library are resolved from the local Maven
    // repository unpacked by scripts/setup-stainless.sh. sbt-stainless also
    // registers `file("stainless")`, but that is relative to the JVM working
    // directory; this one is anchored to the build, so the build also loads
    // when a tool (Metals, IntelliJ) starts sbt from somewhere else.
    resolvers += "stainless-local-repo" at (baseDirectory.value / "stainless").toURI.toASCIIString,

    // A FULL override, not `++=`: kernels are pure Stainless models, and the
    // Stainless library sources injected into the compile emit warnings we do
    // not own — `-Werror` would turn those into build failures.
    scalacOptions := Seq(
      "-deprecation",
      "-feature",
      "-Wconf:src=.*stainless-library.*:silent"
    ),

    publish / skip := true,

    // Default OFF so `compile` (and any IDE import) is an ordinary, fast
    // compile of the models. The `ring4` alias below turns verification on.
    stainlessEnabled := false,

    // Native Z3. Declared only when the jar is present, so a checkout without
    // it degrades to smt-z3 rather than failing dependency resolution.
    stainlessExtraDeps ++= {
      val jar = baseDirectory.value / "unmanaged" / scalaZ3JarName
      if (jar.exists)
        Seq("ch.epfl.lara" % "scalaz3_3" % scalaZ3Version from jar.toURI.toASCIIString)
      else Seq.empty
    },

    // Rewrite `-Xplugin:<stainless-dotty-plugin>` to the ScalaZ3-merged jar.
    // `(Compile / scalacOptions).value` here is the value produced by the
    // settings above plus StainlessPlugin's own — not a self-reference cycle.
    // Taking the plugin path from the option itself (rather than rebuilding it
    // from version constants) keeps this correct if Stainless changes where it
    // stages the jar.
    Compile / scalacOptions := {
      val opts = (Compile / scalacOptions).value
      val log  = streams.value.log
      val z3   = baseDirectory.value / "unmanaged" / scalaZ3JarName
      if (!stainlessEnabled.value) opts
      else opts.map {
        case opt if opt.startsWith("-Xplugin:") &&
                    opt.contains("stainless-dotty-plugin") &&
                    !opt.contains("-merged.jar") =>
          val pluginJar = file(opt.stripPrefix("-Xplugin:"))
          val outDir    = baseDirectory.value / ".ring4" / "plugin"
          "-Xplugin:" + mergeScalaZ3IntoPlugin(pluginJar, z3, outDir, log).getAbsolutePath
        case opt => opt
      }
    }
  )

// Ring 4. `clean` is deliberate: zinc would skip an unchanged compile, and the
// Stainless plugin only runs as part of one. Stainless keeps its own VC cache
// in .stainless-cache, so a re-run of unchanged kernels is still fast.
// Give it heap — Z3 is single-threaded and memory-hungry (see .jvmopts).
addCommandAlias("ring4", "; set stainlessEnabled := true ; clean ; compile")
