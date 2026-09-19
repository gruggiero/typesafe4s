addSbtPlugin("io.getkyo"      % "kyo-compat-plugin" % "1.0.0-RC6")
addSbtPlugin("com.eed3si9n"   % "sbt-projectmatrix" % "0.11.0")
addSbtPlugin("com.eed3si9n"   % "sbt-buildinfo"     % "0.13.1")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt"      % "2.6.2")
addSbtPlugin("com.github.sbt" % "sbt-ci-release"    % "1.12.1")

// Ring 3 — mutation testing. See stryker4s.conf; its mutate/test-filter lists are FIXED and must be
// retargeted per spec, and a matrix cell must be named (e.g. `sbt core/stryker`).
addSbtPlugin("io.stryker-mutator" % "sbt-stryker4s" % "0.21.0")
