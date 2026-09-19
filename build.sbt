import scala.sys.process.Process

import _root_.io.getkyo.compat.CompatBackendAxis
import sbt.VirtualAxis

// Cross-compiled on two Scala 3 minors: 3.8.4 is the PUBLISHED build —
// TASTy is forward-compatible, so a jar compiled on the lower minor is
// consumable by 3.8.x AND 3.9.x callers (the reverse is not true). The
// 3.9.0 cells verify compilation/tests but never publish: both versions
// share the `_3` binary suffix and would collide on the same coordinates.
val scala38 = "3.8.4" // published floor
val scala39 = "3.9.0" // additional verification axis

// The publish guard, shared by `commonSettings` and the client matrix's own
// override. `scalaVersion` alone is NOT enough: `++<version>` overrides a
// projectmatrix cell's own scalaVersion, and `sbt +publishSigned` (ci-release's
// default) issues exactly that — under `++3.8.4` every `*3_9_0` shadow cell
// would report scalaVersion 3.8.4 and become publishable, writing onto its
// 3.8.4 twin's coordinates. The cell id carries the axis and `++` cannot touch
// it, so the two conditions together hold under every cross-build command.
// (`sbt check`'s `checkPublicationContract` evaluates at the default
// resolution and so cannot observe the `++` case — hence the id term here.)
lazy val skipNon38Publish = Def.setting(
  scalaVersion.value != scala38 || thisProject.value.id.endsWith(scala39.replace('.', '_'))
)

val munitVersion           = "1.3.6"
val munitScalacheckVersion = "1.3.1"

// backend effect libraries, declared explicitly so Scala Steward keeps them current
val kyoVersion        = "1.0.0-RC6"
val zioVersion        = "2.1.26"
val catsEffectVersion = "3.7.1"
val fs2Version        = "3.14.0"
val oxVersion         = "1.0.6"
val pekkoVersion      = "1.7.0"

// JSON adapter libraries — one per adapter module (spec: json-adapters)
val circeVersion    = "0.14.15"
val zioJsonVersion  = "0.7.44"
val jsoniterVersion = "2.36.7"
val upickleVersion  = "4.3.2"

// The Pekko backend uses the Future compatibility cell (`kyo-compat-future`) with Pekko Streams. A separate axis name prevents it from
// being deduplicated with the implicit Future anchor. The explicit coordinates make its rows resolve `kyo-compat-future`; the plugin
// would otherwise inject a nonexistent `kyo-compat-pekko` dependency.
val PekkoLib = CompatBackendAxis.external("pekko", "Pekko", "-pekko", Set("jvm"), "io.getkyo", "kyo-compat-future", kyoVersion)

// Kyo removed its Cats Effect binding in 1.0.0-RC6, so the `ce` axis binds locally to the vendored `typesafe4s-compat-ce` module.
// The name and suffixes match the removed built-in, which keeps cell ids and artifact names unchanged.
val CeLib = CompatBackendAxis.local("ce", "Ce", "-ce", Set("jvm"))

inThisBuild(
  List(
    scalaVersion     := scala38,
    organization     := "io.github.gruggiero",
    homepage         := Some(url("https://github.com/gruggiero/typesafe4s")),
    licenses         := List(License.Apache2),
    scmInfo          := Some(ScmInfo(url("https://github.com/gruggiero/typesafe4s/"), "scm:git:git@github.com:gruggiero/typesafe4s.git")),
    developers       := List(Developer("gruggiero", "Giovanni Ruggiero", "giovanni.ruggiero@gmail.com", url("https://github.com/gruggiero"))),
    compatKyoVersion := kyoVersion
  )
)

name := "typesafe4s"

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias(
  "check",
  "checkCoreDependencies; checkPublicationContract; checkSharedSources; checkAdapterDependencies; checkReleaseReadiness; all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck"
)

// spec: wire-codec — Requirement: The core carries no third-party runtime
// dependency. A module's dependency list is a build fact no test can observe,
// so this is a task, wired into `sbt check` so every lint run re-verifies it.
// The per-adapter assertions land with the adapter modules (tasks §11).
lazy val checkCoreDependencies = taskKey[Unit]("asserts typesafe4s-core carries no third-party runtime dependency")
checkCoreDependencies := {
  // cross-compiled cell names are computed — `.value` cannot be scoped per
  // name at task level, so resolve them through the project extractor
  val ext               = Project.extract(state.value)
  var st                = state.value
  def jarsOf(p: String) = {
    val (ns, jars) = ext.runTask(LocalProject(p) / Compile / externalDependencyClasspath, st)
    st = ns; jars
  }
  // both scala-version cells — the canonical `core` (3.8.4, published) and
  // `core3_9_0` (verification twin) compile the same sources
  List("core", "core3_9_0").foreach { p =>
    val thirdParty = jarsOf(p).filter { attributed =>
      attributed.get(moduleID.key) match {
        case Some(m) => m.organization != "org.scala-lang"
        case None    => true
      }
    }
    assert(
      thirdParty.isEmpty,
      s"$p must carry no third-party runtime dependency; found: ${thirdParty.map(_.data.getName).mkString(", ")}"
    )
  }
}

// spec: effect-portability — Requirement: Each ecosystem publishes its own
// artifact; the Future anchor row is a compile-only baseline, never published;
// every artifact declares its ecosystem libraries explicitly; all artifacts
// compile the same shared sources. Publication facts and declared dependency
// lists are build facts no test can observe — asserted here, wired into
// `sbt check` so every lint run re-verifies the contract.
lazy val checkPublicationContract =
  taskKey[Unit]("asserts the published artifact set, the baseline's absence, and per-row declared dependencies")
checkPublicationContract := {
  // matrix cell ids carry the full-version suffix under cross-compilation:
  // `<matrix><Backend>3_8_4` is the published cell, `<matrix><Backend>3_9_0`
  // its verification twin. Cell names are computed — `.value` cannot be
  // scoped per name at task level, so resolve settings through the project
  // extractor (`get`) and tasks through `runTask`.
  val ext               = Project.extract(state.value)
  var st                = state.value
  def skipOf(p: String) = {
    val (ns, flag) = ext.runTask(LocalProject(p) / publish / skip, st)
    st = ns; flag
  }
  def depsOf(p: String) = ext.get(LocalProject(p) / libraryDependencies)
  def dirsOf(p: String) = ext.get(LocalProject(p) / Compile / unmanagedSourceDirectories)

  val backends                = List("Zio", "Ce", "Ox", "Kyo", "Pekko")
  val versions                = List("3_8_4", "3_9_0")
  def cells(matrix: String)   = backends.flatMap(b => versions.map(v => s"$matrix$b$v"))
  def anchors(matrix: String) = versions.map(v => s"${matrix}Future$v")

  // org+module granularity: an org-level check is vacuous on the kyo row, whose
  // `io.getkyo` compat artifact is plugin-injected and would satisfy the org alone
  val rows           = List(
    ("Zio", ("dev.zio", "zio")),
    ("Ce", ("org.typelevel", "cats-effect")),
    ("Ox", ("com.softwaremill.ox", "core")),
    ("Kyo", ("io.getkyo", "kyo-core")),
    ("Pekko", ("org.apache.pekko", "pekko-stream"))
  )
  val publishedDeps  = rows.map { case (b, d) => (s"client${b}3_8_4", d, depsOf(s"client${b}3_8_4"), dirsOf(s"client${b}3_8_4")) }
  val publishedSkips = publishedDeps.map { case (p, _, _, _) => p -> skipOf(p) }
  publishedDeps.zip(publishedSkips).foreach { case ((p, (org, mod), deps, dirs), (_, skip)) =>
    assert(!skip, s"$p is marked publish/skip — a published row must publish")
    assert(
      deps.exists(d => d.organization == org && d.name == mod),
      s"$p declares no $org %% $mod dependency — each artifact declares its ecosystem libraries explicitly"
    )
    assert(
      dirs.exists(_.getPath.contains("/shared/src/")),
      s"$p does not compile the shared sources — all artifacts must compile the same shared sources"
    )
  }

  // the 3.9.0 cells verify compilation but must never publish: both minors
  // emit the same `_3` artifact suffix, so publishing both would collide on
  // identical coordinates — the lower build is the widest-compatible anyway
  val shadowSkips = backends.map(b => s"client${b}3_9_0" -> skipOf(s"client${b}3_9_0"))
  shadowSkips.foreach { case (p, skip) =>
    assert(skip, s"$p is publishable — only the 3.8.4 cell of each row may publish (the `_3` suffix would collide)")
  }

  val anchorSkips = anchors("client").map(p => p -> skipOf(p))
  val anchorDirs  = anchors("client").map(p => p -> dirsOf(p))
  anchorSkips.foreach { case (p, skip) =>
    assert(skip, s"$p is the compile-only baseline — it must never publish")
  }
  anchorDirs.foreach { case (p, dirs) =>
    assert(
      dirs.exists(_.getPath.contains("/shared/src/")),
      s"$p does not compile the shared sources — the baseline must compile the same shared sources"
    )
  }

  // spec: release-readiness — Scenario: A tag publishes exactly the
  // published set: the core, every published client row, every adapter,
  // and the vendored compat-ce binding the ce cells link to — and nothing
  // else. The anchor and shadow cells' exclusion is asserted above; here
  // the rest of the published set proves publishable and the non-artifacts
  // prove not.
  val adapterSkips = List(
    "jsonCirce"    -> skipOf("jsonCirce"),
    "jsonZio"      -> skipOf("jsonZio"),
    "jsonJsoniter" -> skipOf("jsonJsoniter"),
    "jsonUpickle"  -> skipOf("jsonUpickle")
  )
  adapterSkips.foreach { case (p, skip) =>
    assert(!skip, s"$p is marked publish/skip — every adapter must publish")
  }
  assert(!skipOf("core"), "core is marked publish/skip — the published set includes the core")
  assert(
    !skipOf("compatCe"),
    "compatCe is marked publish/skip — client-ce links to it, so it must publish"
  )
  val nonPublished = List("root" -> skipOf("root")) ++
    (cells("integrationTests") ++ anchors("integrationTests") ++
      cells("examples") ++ anchors("examples") ++
      // ceConformance binds only the Ce backend plus the implicit Future anchor
      List("ceConformanceCe3_8_4", "ceConformanceCe3_9_0", "ceConformanceFuture3_8_4", "ceConformanceFuture3_9_0"))
      .map(p => p -> skipOf(p))
  nonPublished.foreach { case (p, skip) =>
    assert(
      skip,
      s"$p is publishable — only the core, the five client rows, the four adapters and compat-ce may publish"
    )
  }
}

// spec: effect-portability — Scenario: Shared sources name no ecosystem.
// The compile-time tier is classpath isolation (an ecosystem's types do not
// resolve in shared cells); this is the belt-and-braces text scan that
// catches a fully-qualified ecosystem reference an import-free snippet
// could otherwise smuggle into a shared source file.
lazy val checkSharedSources = taskKey[Unit]("asserts shared sources name no ecosystem type")
checkSharedSources := {
  val root       = (ThisBuild / baseDirectory).value
  val dirs       = Seq(
    root / "typesafe4s-client" / "shared" / "src",
    root / "integration-tests" / "shared" / "src",
    root / "examples" / "shared" / "src"
  )
  val files      = dirs.filter(_.exists).flatMap(d => (d ** "*.scala").get)
  val ecosystems = List("zio", "cats", "ox", "org.apache.pekko")
  val violations = files.flatMap { f =>
    IO.readLines(f).zipWithIndex.collect {
      case (line, i) if ecosystems.exists(p => line.matches(s".*\\b${java.util.regex.Pattern.quote(p)}\\s*\\..*")) =>
        s"${f.getPath}:${i + 1}: ${line.trim}"
      case (line, i) if line.matches(".*\\bkyo\\s*\\.(?!compat).*")                                                =>
        s"${f.getPath}:${i + 1}: ${line.trim}"
    }
  }
  assert(
    violations.isEmpty,
    s"shared sources must name no ecosystem type; found:\n${violations.mkString("\n")}"
  )
}

// spec: json-adapters — Scenario: Adapters do not drag each other in. Each
// adapter module must carry exactly its own JSON library and nothing else:
// the whole point is that a circe user never pays for zio-json's jar.
lazy val checkAdapterDependencies =
  taskKey[Unit]("asserts each JSON adapter module declares exactly its own library and drags in no other's")
checkAdapterDependencies := {
  // `.value` cannot be used inside closures — each adapter's facts are
  // resolved at the task top level first. Both Compile and Test classpaths
  // are read: a competing library declared `% Test` still drags its jar onto
  // the module's test path.
  val circeDeps        = (LocalProject("jsonCirce") / Compile / externalDependencyClasspath).value
  val zioDeps          = (LocalProject("jsonZio") / Compile / externalDependencyClasspath).value
  val jsoniterDeps     = (LocalProject("jsonJsoniter") / Compile / externalDependencyClasspath).value
  val upickleDeps      = (LocalProject("jsonUpickle") / Compile / externalDependencyClasspath).value
  val circeTestDeps    = (LocalProject("jsonCirce") / Test / externalDependencyClasspath).value
  val zioTestDeps      = (LocalProject("jsonZio") / Test / externalDependencyClasspath).value
  val jsoniterTestDeps = (LocalProject("jsonJsoniter") / Test / externalDependencyClasspath).value
  val upickleTestDeps  = (LocalProject("jsonUpickle") / Test / externalDependencyClasspath).value
  val circeDecl        = (LocalProject("jsonCirce") / libraryDependencies).value
  val zioDecl          = (LocalProject("jsonZio") / libraryDependencies).value
  val jsoniterDecl     = (LocalProject("jsonJsoniter") / libraryDependencies).value
  val upickleDecl      = (LocalProject("jsonUpickle") / libraryDependencies).value

  // resolved artifacts carry the cross suffix (circe-core_3); match on the
  // binary-name prefix so the check is cross-version agnostic. A jar with no
  // module ID (an unmanaged lib/*.jar) counts as a third-party artifact, not
  // a pass — the same discipline checkCoreDependencies applies to core.
  def resolved(jars: Seq[Attributed[File]]): Seq[String] =
    jars.flatMap { j =>
      j.get(moduleID.key) match {
        case Some(m) if m.organization != "org.scala-lang" => Some(s"${m.organization}:${m.name}")
        case Some(_)                                       => None
        // scala-library arrives without a module ID on plain projects — only
        // non-Scala jars lacking coordinates count as unmanaged
        case None if j.data.getName.startsWith("scala")    => None
        case None                                          => Some(s"unmanaged:${j.data.getName}")
      }
    }
  def isLib(resolved: String, lib: String): Boolean      =
    resolved == lib || resolved.startsWith(s"${lib}_")

  val libraries = List(
    "io.circe:circe-core",
    "dev.zio:zio-json",
    "com.github.plokhotnyuk.jsoniter-scala:jsoniter-scala-core",
    "com.lihaoyi:ujson"
  )
  // test frameworks are the only other dependencies an adapter may declare,
  // and only in the Test configuration
  val testLibs  = List("org.scalameta:munit", "org.scalameta:munit-scalacheck")
  // (project, own library, resolved classpath incl. Test scope, declared deps)
  val adapters  = List(
    ("jsonCirce", libraries(0), resolved(circeDeps) ++ resolved(circeTestDeps), circeDecl),
    ("jsonZio", libraries(1), resolved(zioDeps) ++ resolved(zioTestDeps), zioDecl),
    ("jsonJsoniter", libraries(2), resolved(jsoniterDeps) ++ resolved(jsoniterTestDeps), jsoniterDecl),
    ("jsonUpickle", libraries(3), resolved(upickleDeps) ++ resolved(upickleTestDeps), upickleDecl)
  )
  adapters.foreach { case (proj, own, deps, decl) =>
    assert(
      decl.exists(d => s"${d.organization}:${d.name}" == own),
      s"$proj does not declare its own JSON library ($own) directly"
    )
    assert(deps.exists(d => isLib(d, own)), s"$proj's classpath does not contain its own JSON library ($own)")
    val leaked    = deps.filter(d => libraries.exists(l => isLib(d, l)) && !isLib(d, own))
    assert(
      leaked.isEmpty,
      s"$proj drags in another adapter's JSON library: ${leaked.mkString(", ")} — adapters must not drag each other in"
    )
    val unmanaged = deps.filter(_.startsWith("unmanaged:"))
    assert(unmanaged.isEmpty, s"$proj carries unmanaged jars with no module ID: ${unmanaged.mkString(", ")}")
    // declared exclusivity: anything beyond the own library and Test-scoped
    // test frameworks is a leak regardless of what resolves onto the classpath
    val strangers = decl.filter { d =>
      val coord = s"${d.organization}:${d.name}"
      coord != own && d.organization != "org.scala-lang" && // autoScalaLibrary injects the stdlib as a declared dep
        !(d.configurations.exists(_.contains("test")) && testLibs.exists(t => coord == t || coord.startsWith(s"${t}_")))
    }
    assert(
      strangers.isEmpty,
      s"$proj declares dependencies beyond its own library and Test-scoped test frameworks: ${strangers.map(d => s"${d.organization}:${d.name}").mkString(", ")}"
    )
  }
}

// spec: release-readiness — the tier-4 assertions: the README states the
// coordinates, the disambiguation and the 0.1 backend scope; the CI workflow
// covers every row and publishes on tags; every published row carries a
// compiling quickstart and the confidence-gated routing example. These are
// repo-file facts no test classpath can observe — asserted here, wired into
// `sbt check`.
lazy val checkReleaseReadiness =
  taskKey[Unit]("asserts the README, CI workflow and per-row examples satisfy the release-readiness spec")
checkReleaseReadiness := {
  val root = (ThisBuild / baseDirectory).value

  // readme-states-coordinates-and-scope — Requirement: The README states
  // what a new caller needs
  val readme = IO.read(root / "README.md")
  assert(
    readme.contains("io.github.gruggiero"),
    "README does not state the groupId — a caller cannot name the dependency to add"
  )
  assert(
    readme.contains("Lightbend"),
    "README is missing the Lightbend disambiguation"
  )
  assert(
    List(
      "typesafe4s-client-zio",
      "typesafe4s-client-ce",
      "typesafe4s-client-ox",
      "typesafe4s-client-kyo",
      "typesafe4s-client-pekko"
    ).forall(readme.contains),
    "README does not record which backends ship at 0.1 — the install coordinates must name all five artifacts"
  )
  assert(
    readme.toLowerCase.contains("quickstart") || readme.toLowerCase.contains("quick start"),
    "README has no quickstart section"
  )

  // ci-covers-every-row — Requirement: Every row is built, tested and linted
  // in CI; Scenario: A broken row fails the build (the matrix names the row);
  // Scenario: A tag publishes exactly the published set (ci-release on tags)
  val workflow   = root / ".github" / "workflows" / "ci.yml"
  assert(workflow.exists, ".github/workflows/ci.yml does not exist — every row must be built, tested and linted in CI")
  // filesystem presence is not enough — a gitignored workflow satisfies
  // `exists` while a clean clone has no CI at all (Ring-8 F3)
  assert(
    Process(Seq("git", "ls-files", "--error-unmatch", ".github/workflows/ci.yml"), root).! == 0,
    ".github/workflows/ci.yml is not tracked by git — commit it (check .gitignore)"
  )
  val ci         = IO.read(workflow)
  // cross-compiled rows: CI must name both version-suffixed cells — the
  // published 3.8.4 cell and its 3.9.0 verification twin
  val scalaCells = List("3_8_4", "3_9_0")
  val rows       = List("Zio", "Ce", "Ox", "Kyo", "Pekko")
  List(
    "core",
    "core3_9_0",
    "jsonCirce",
    "jsonZio",
    "jsonJsoniter",
    "jsonUpickle",
    "parityAll",
    "sbt check" // distinctive — bare "check" is satisfied by actions/checkout (Ring-8 F5)
  ).++(
    rows.flatMap(r => scalaCells.flatMap(v => List(s"client$r$v", s"examples$r$v", s"integrationTests$r$v"))) ++
      scalaCells.flatMap(v => List(s"clientFuture$v", s"examplesFuture$v", s"integrationTestsFuture$v", s"ceConformanceCe$v"))
  ).foreach { token =>
    assert(ci.contains(token), s"ci.yml does not mention '$token' — the matrix must cover every row")
  }
  assert(
    ci.contains("ci-release") && ci.contains("refs/tags/"),
    "ci.yml does not publish on tags — sbt-ci-release must run for tagged releases"
  )

  // example-compiles ×5 — Requirement: Every published backend has a
  // runnable example; Scenario: A confidence-gated routing example
  List("zio", "ce", "ox", "kyo", "pekko").foreach { row =>
    val dir = root / "examples" / row / "src" / "main" / "scala" / "typesafe4s" / "examples"
    assert(
      (dir / "Quickstart.scala").exists,
      s"examples/$row carries no Quickstart.scala — every published backend needs a runnable example"
    )
    assert(
      (dir / "ConfidenceRouting.scala").exists,
      s"examples/$row carries no ConfidenceRouting.scala — the routing example is required per row"
    )
  }
}

// Compile and test every row on both Scala minors. The Future anchor rows are compile-only: never published, boot nothing.
addCommandAlias(
  "testUnit",
  "all core/test core3_9_0/test " +
    "clientZio3_8_4/test clientZio3_9_0/test clientCe3_8_4/test clientCe3_9_0/test " +
    "clientOx3_8_4/test clientOx3_9_0/test clientPekko3_8_4/test clientPekko3_9_0/test " +
    "clientKyo3_8_4/test clientKyo3_9_0/test " +
    "clientFuture3_8_4/Test/compile clientFuture3_9_0/Test/compile " +
    "integrationTestsFuture3_8_4/Test/compile integrationTestsFuture3_9_0/Test/compile; " +
    // adapters are plain projects — `+` iterates their crossScalaVersions (both minors)
    "+jsonCirce/test; +jsonZio/test; +jsonJsoniter/test; +jsonUpickle/test; " +
    // The conformance suite has wall-clock parallelism checks. Run it after the parallel block so forked test cells do not starve it.
    "ceConformanceCe3_8_4/test; ceConformanceCe3_9_0/test"
)
addCommandAlias("conformanceCe", "ceConformanceCe3_8_4/test; ceConformanceCe3_9_0/test")

// Ring 5 (cross-backend parity): one suite, written once against CIO, executed on every row.
// Parity runs on the published 3.8.4 cells; the 3.9.0 integration cells are compile-verified in CI.
addCommandAlias("parityZio", "integrationTestsZio3_8_4/test")
addCommandAlias("parityCe", "integrationTestsCe3_8_4/test")
addCommandAlias("parityOx", "integrationTestsOx3_8_4/test")
addCommandAlias("parityKyo", "integrationTestsKyo3_8_4/test")
addCommandAlias("parityPekko", "integrationTestsPekko3_8_4/test")
addCommandAlias(
  "parityAll",
  "integrationTestsZio3_8_4/test; integrationTestsCe3_8_4/test; integrationTestsOx3_8_4/test; " +
    "integrationTestsKyo3_8_4/test; integrationTestsPekko3_8_4/test"
)

lazy val root = project
  .in(file("."))
  .settings(publish / skip := true)
  .aggregate(
    core.projectRefs ++ client.projectRefs ++ integrationTests.projectRefs ++ examples.projectRefs ++
      Seq[ProjectReference](compatCe, jsonCirce, jsonZio, jsonJsoniter, jsonUpickle): _*
  )

// Pure sans-IO core: JSON AST and codecs, the question/answer model, the wire contract, the error algebra. Zero external dependencies.
lazy val core = (projectMatrix in file("typesafe4s-core"))
  .settings(name := "typesafe4s-core")
  .settings(commonSettings)
  .settings(parallelUnitTests)
  // the canonical `core` cell is the published 3.8.4 build; `core3_9_0` is
  // compile+test only (the `_3` suffix collision forbids publishing both)
  .defaultAxes(VirtualAxis.jvm, VirtualAxis.scalaVersionAxis(scala38, scala38))
  .customRow(
    autoScalaLibrary = true,
    axisValues = Seq(VirtualAxis.jvm, VirtualAxis.scalaVersionAxis(scala38, scala38)),
    process = identity[Project] _
  )
  .customRow(
    autoScalaLibrary = true,
    axisValues = Seq(VirtualAxis.jvm, VirtualAxis.scalaVersionAxis(scala39, scala39)),
    process = identity[Project] _
  )

// spec: json-adapters — one adapter module per documented JSON library. Each is a
// plain project (backend-agnostic, not a matrix row) carrying exactly its own
// library; `checkAdapterDependencies` in `sbt check` enforces the isolation.
// test->test on core so adapter suites can reuse the shared JSON generators.
lazy val jsonCirce = project
  .in(file("typesafe4s-json-circe"))
  .settings(name := "typesafe4s-circe")
  .settings(commonSettings)
  .dependsOn(LocalProject("core") % "compile->compile;test->test")
  .settings(libraryDependencies += "io.circe" %% "circe-core" % circeVersion)

lazy val jsonZio = project
  .in(file("typesafe4s-json-zio"))
  .settings(name := "typesafe4s-zio-json")
  .settings(commonSettings)
  .dependsOn(LocalProject("core") % "compile->compile;test->test")
  .settings(libraryDependencies += "dev.zio" %% "zio-json" % zioJsonVersion)

// jsoniter-scala ships no public AST — callers hand the adapter encoded JSON
// (bytes or text, as produced by jsoniter's writers), which the adapter
// validates through the core parser.
lazy val jsonJsoniter = project
  .in(file("typesafe4s-json-jsoniter"))
  .settings(name := "typesafe4s-jsoniter")
  .settings(commonSettings)
  .dependsOn(LocalProject("core") % "compile->compile;test->test")
  .settings(libraryDependencies += "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterVersion)

lazy val jsonUpickle = project
  .in(file("typesafe4s-json-upickle"))
  .settings(name := "typesafe4s-upickle")
  .settings(commonSettings)
  .dependsOn(LocalProject("core") % "compile->compile;test->test")
  .settings(libraryDependencies += "com.lihaoyi" %% "ujson" % upickleVersion)

// The Cats Effect binding of the `kyo.compat` package, vendored because Kyo removed it upstream; `typesafe4s-compat-ce/README.md`
// records the provenance. The `ce` cells of every matrix bind to this project through `bindLocally`.
lazy val compatCe = project
  .in(file("typesafe4s-compat-ce"))
  .settings(name := "typesafe4s-compat-ce")
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "co.fs2"        %% "fs2-core"    % fs2Version
    )
  )

// Run the conformance suite bundled inside `kyo-compat-plugin` against the vendored `ce` binding, so that a change to the upstream
// `kyo.compat` contract is reported here as a test failure rather than as a compile error in a downstream module.
lazy val ceConformance = (projectMatrix in file("typesafe4s-compat-ce/.conformance"))
  .settings(publish / skip := true)
  .compatLibrary(CeLib)(VirtualAxis.jvm)(Seq(scala38, scala39))
  .bindLocally(CeLib, compatCe)
  .compatConformance()

// The runtime, written once against kyo-compat and cross-published per backend.
lazy val client = (projectMatrix in file("typesafe4s-client"))
  // test->test so shared-client test sources (typed contracts, spec property suites) compile against
  // core test types before their Step-3 promotion into core main — the same mapping integration-tests uses.
  .dependsOn(core % "compile->compile;test->test")
  .enablePlugins(BuildInfoPlugin)
  .settings(name := "typesafe4s-client")
  .settings(commonSettings)
  .settings(parallelUnitTests)
  .settings(
    // compatLibrary emits an implicit Future anchor row; it is a compile-only baseline, never published.
    // The 3.9.0 cells never publish either — the `_3` suffix collides with the published 3.8.4 cells.
    publish / skip   := moduleName.value.endsWith("-future") || skipNon38Publish.value,
    // the SDK reports its own version in the User-Agent header; moduleName identifies the backend row
    // ("typesafe4s-client-pekko" etc.) so shared parity suites can assert per-row declared divergence
    buildInfoKeys    := Seq[BuildInfoKey](version, moduleName),
    buildInfoPackage := "typesafe4s.client",
    // pin the backend effect libs per cell rather than inheriting them transitively from kyo-compat-<backend>
    libraryDependencies ++= {
      val m = moduleName.value
      if (m.endsWith("-zio")) Seq("dev.zio" %% "zio" % zioVersion, "dev.zio" %% "zio-streams" % zioVersion)
      else if (m.endsWith("-ce")) Seq("org.typelevel" %% "cats-effect" % catsEffectVersion, "co.fs2" %% "fs2-core" % fs2Version)
      else if (m.endsWith("-ox")) Seq("com.softwaremill.ox" %% "core" % oxVersion)
      else if (m.endsWith("-kyo")) Seq("io.getkyo" %% "kyo-core" % kyoVersion)
      else if (m.endsWith("-pekko"))
        Seq(
          "org.apache.pekko" %% "pekko-stream"      % pekkoVersion,
          "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion
        )
      else Seq.empty
    },
    // spec: effect-portability — `published-shared` test sources compile into every PUBLISHED row but not
    // the compile-only future baseline: facade-referencing suites (`typesafe4s.TypesafeClient`) exist
    // exactly once and run on the five published artifacts.
    Test / unmanagedSourceDirectories ++= {
      val dir = (ThisBuild / baseDirectory).value / "typesafe4s-client" / "published-shared" / "src" / "test" / "scala"
      if (moduleName.value.endsWith("-future")) Seq.empty else Seq(dir)
    }
  )
  .compatLibrary(KyoLib, ZioLib, CeLib, OxLib, PekkoLib)(VirtualAxis.jvm)(Seq(scala38, scala39))
  .bindLocally(CeLib, compatCe)

// Ring 5 — cross-backend parity. ONE behavioural suite, written against CIO, recompiled and executed for every backend row.
// A test that exists only in one row proves nothing about the others.
lazy val integrationTests = (projectMatrix in file("integration-tests"))
  // test->test so the shared ManualClock (client test sources) is visible here: the deterministic timing suite is written once
  // against the carrier and must compile into every row.
  .dependsOn(client % "compile->compile;test->test", core % "test->test")
  .settings(name := "integration-tests")
  .settings(commonSettings)
  .settings(publish / skip := true)
  // spec: effect-portability — `published-shared` test sources compile into every PUBLISHED row but not
  // the compile-only future baseline: the lowering/lifting property names each row's own facade
  // conversions, which exist only on the five published artifacts.
  .settings(
    Test / unmanagedSourceDirectories ++= {
      val dir = (ThisBuild / baseDirectory).value / "integration-tests" / "published-shared" / "src" / "test" / "scala"
      if (moduleName.value.endsWith("-future")) Seq.empty else Seq(dir)
    }
  )
  .compatLibrary(KyoLib, ZioLib, CeLib, OxLib, PekkoLib)(VirtualAxis.jvm)(Seq(scala38, scala39))
  .bindLocally(CeLib, compatCe)

// spec: release-readiness — Requirement: Every published backend has a
// runnable example. One example matrix mirroring the client rows: per-row
// sources under `examples/<row>/` express each backend's own idiom; the
// shared `Demo` fixtures (stub exchange, canned response, question set)
// compile into every cell — including the future anchor row, which stays
// compile-only. Examples are never published; `sbt check` asserts the
// per-row files exist and CI compiles every cell so they cannot rot.
lazy val examples = (projectMatrix in file("examples"))
  .dependsOn(client)
  .settings(name := "typesafe4s-examples")
  .settings(commonSettings)
  .settings(publish / skip := true)
  .compatLibrary(KyoLib, ZioLib, CeLib, OxLib, PekkoLib)(VirtualAxis.jvm)(Seq(scala38, scala39))
  .bindLocally(CeLib, compatCe)

lazy val commonSettings = Def.settings(
  // cross-compile every module on both minors; only the 3.8.4 build may
  // publish (both share the `_3` suffix — a second publish would collide
  // on identical coordinates, and the lower build is the widest-compatible)
  crossScalaVersions := Seq(scala38, scala39),
  publish / skip     := skipNon38Publish.value,
  scalacOptions ++= Seq(
    "-deprecation",
    "-no-indent",
    "-release",
    "21",
    "-Wunused:imports,params,privates,implicits,explicits",
    "-Wvalue-discard",
    "-Werror",
    "-Xkind-projector"
  ),
  libraryDependencies ++= Seq(
    "org.scalameta" %% "munit"            % munitVersion           % Test,
    "org.scalameta" %% "munit-scalacheck" % munitScalacheckVersion % Test
  ),
  Test / fork        := true
)

lazy val parallelUnitTests = Def.settings(Test / testForkedParallel := true)
