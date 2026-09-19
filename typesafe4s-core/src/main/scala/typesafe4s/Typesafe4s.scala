package typesafe4s

/**
  * `typesafe4s-core` is the pure, sans-IO module: the JSON AST and codecs, the question/answer model, the wire contract and the error
  * algebra land here, and it carries ZERO runtime dependencies — asserted by `checkCoreDependencies` in build.sbt, wired into
  * `sbt check`. That last point is a contract, not a preference — see `openspec/project.md`.
  *
  * The specs under `openspec/changes/add-typesafe4s-sdk/specs/` are the source of truth.
  */
object Typesafe4s {

  /**
    * Marker proving the core module compiles under `-Werror` with the project's warning set.
    */
  final val BuildStub: String = "typesafe4s-core"
}
