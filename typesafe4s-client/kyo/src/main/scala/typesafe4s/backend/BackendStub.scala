package typesafe4s.backend

/**
  * Build stub for the kyo row. The real facade supplies exactly two transformations — `lower[A](CIO[A]): F[A]` and
  * `lift[A](F[A]): CIO[A]` — and everything else is derived in the shared `LoweredClient`. See the `effect-portability` spec.
  */
private[typesafe4s] object BackendStub {

  /**
    * The backend this row publishes for.
    */
  final val Backend: String = "Kyo"
}
