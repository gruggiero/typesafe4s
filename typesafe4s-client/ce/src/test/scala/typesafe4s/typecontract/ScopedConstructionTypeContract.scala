package typesafe4s.typecontract

import cats.effect.{IO, Resource}

import typesafe4s.TypesafeClient
import typesafe4s.client.{Transport, TypesafeConfig}

// ============================================================================
// TYPED CONTRACT — spec: client-configuration (change: add-typesafe4s-sdk,
// spec 7/8). Scoped construction — Cats Effect row.
//
// Gate 1 artifact — pending approval.
//
// The Cats Effect idiom is `Resource`: `resource` returns
// `Resource[IO, TypesafeClient]` — `Resource.make(IO.pure(of(...)))(c =>
// c.close)`. `use` runs the release on success, error and cancellation.
// ============================================================================

private object ScopedConstructionTypeContract {

  object Facade {
    def resource(config: TypesafeConfig): Resource[IO, TypesafeClient] = ???

    def resource(config: TypesafeConfig, transport: Transport): Resource[IO, TypesafeClient] = ???
  }
}
