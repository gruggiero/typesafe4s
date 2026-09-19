package typesafe4s.typecontract

import ox.Ox

import typesafe4s.TypesafeClient
import typesafe4s.client.{Transport, TypesafeConfig}

// ============================================================================
// TYPED CONTRACT — spec: client-configuration (change: add-typesafe4s-sdk,
// spec 7/8). Scoped construction — Ox row.
//
// Gate 1 artifact — pending approval.
//
// The Ox idiom is the `supervised` scope: `scoped` returns the client inside
// the caller's scope — `Ox ?=> TypesafeClient` — after registering a scope
// finalizer (`Ox` extends `ResourceScope` → `addFinalizer`) that runs the
// release when the scope ends, on success or failure. The finalizer is a
// `Function0[Unit]` with no `Ox` in scope, so it runs the carrier's close on
// the carrier's own runtime (`CIO.unsafeRun` + `Await.result`).
// ============================================================================

private object ScopedConstructionTypeContract {

  object Facade {
    def scoped(config: TypesafeConfig)(using Ox): TypesafeClient = ???

    def scoped(config: TypesafeConfig, transport: Transport)(using Ox): TypesafeClient = ???
  }
}
