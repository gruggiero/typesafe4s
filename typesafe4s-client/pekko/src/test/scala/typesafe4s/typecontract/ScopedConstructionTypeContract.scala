package typesafe4s.typecontract

import scala.concurrent.{ExecutionContext, Future}

import typesafe4s.TypesafeClient
import typesafe4s.client.{Transport, TypesafeConfig}

// ============================================================================
// TYPED CONTRACT — spec: client-configuration (change: add-typesafe4s-sdk,
// spec 7/8). Scoped construction — Pekko row.
//
// Gate 1 artifact — pending approval.
//
// `scala.concurrent.Future` has no scope idiom — the determined-moment
// release a Future carrier can offer is the bracket shape: `use` runs `body`
// with the client and then runs `client.close` on EVERY outcome — `body`'s
// success or failure — before completing with `body`'s result. A close
// failure on a failed body does not mask `body`'s failure.
// ============================================================================

private object ScopedConstructionTypeContract {

  object Facade {
    def use[A](config: TypesafeConfig)(
      body: TypesafeClient => Future[A]
    )(using ExecutionContext): Future[A] = ???

    def use[A](config: TypesafeConfig, transport: Transport)(
      body: TypesafeClient => Future[A]
    )(using ExecutionContext): Future[A] = ???
  }
}
