package typesafe4s.typecontract

import kyo.{<, Scope, Sync}

import typesafe4s.TypesafeClient
import typesafe4s.client.{Transport, TypesafeConfig}

// ============================================================================
// TYPED CONTRACT — spec: client-configuration (change: add-typesafe4s-sdk,
// spec 7/8). Scoped construction — Kyo row.
//
// Gate 1 artifact — pending approval.
//
// The Kyo idiom is the `Scope` effect: `scoped` returns
// `TypesafeClient < (Scope & Sync)` — `Scope.acquireRelease(of(...))(c =>
// c.close)` — the release (`Any < (Async & Abort[Throwable])`, which
// `close`'s pending set satisfies) runs when the enclosing `Scope.run`
// finishes, on success or failure.
// ============================================================================

private object ScopedConstructionTypeContract {

  object Facade {
    def scoped(config: TypesafeConfig): TypesafeClient < (Scope & Sync) = ???

    def scoped(config: TypesafeConfig, transport: Transport): TypesafeClient < (Scope & Sync) = ???
  }
}
