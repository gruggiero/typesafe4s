package typesafe4s.typecontract

import zio.{Scope, ZIO}

import typesafe4s.{TypesafeClient, TypesafeException}
import typesafe4s.client.{Transport, TypesafeConfig}

// ============================================================================
// TYPED CONTRACT — spec: client-configuration (change: add-typesafe4s-sdk,
// spec 7/8). Scoped construction — ZIO row.
//
// Gate 1 artifact — pending approval.
//
// The ZIO idiom for a value released at a determined moment is `Scope`:
// `scoped` returns `ZIO[Scope, TypesafeException, TypesafeClient]` —
// `ZIO.acquireRelease(ZIO.succeed(of(...)))(c => c.close.orDie)`. Release
// cannot fail the typed channel: a close failure is a defect, not a typed
// error. The caller runs it inside `ZIO.scoped`; scope exit — success or
// failure — runs the release.
// ============================================================================

private object ScopedConstructionTypeContract {

  object Facade {
    def scoped(config: TypesafeConfig): ZIO[Scope, TypesafeException, TypesafeClient] = ???

    def scoped(config: TypesafeConfig, transport: Transport): ZIO[Scope, TypesafeException, TypesafeClient] = ???
  }
}
