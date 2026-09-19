package typesafe4s.typecontract

import scala.concurrent.Future

import kyo.compat.CIO

import typesafe4s.TypesafeClient
import typesafe4s.client.{ApiSurface, Client, Transport, TypesafeConfig}

// ============================================================================
// TYPED CONTRACT — spec: effect-portability (change: add-typesafe4s-sdk, spec 6/8)
// Facade contract — Pekko row.
//
// Gate 1 artifact, APPROVED. The declarations were promoted to
// typesafe4s-client/pekko/src/main/scala/typesafe4s/TypesafeClient.scala at
// Step 3; this file remains as a living compile-time assertion.
//
// Approved surface:
//   type TypesafeClient = Client[Future]
//   object TypesafeClient extends ApiSurface[Future]
//   private[typesafe4s] lower/lift — the two conversions; `lower`
//     materialises the carrier's `LocalCtx ?=> Future[A]` at the root ctx.
// ============================================================================

private object FacadeTypeContract {

  def witnessAlias(client: TypesafeClient): Client[Future] = client

  def witnessOf(config: TypesafeConfig): TypesafeClient =
    TypesafeClient.of(config)

  def witnessOfTransport(config: TypesafeConfig, transport: Transport): TypesafeClient =
    TypesafeClient.of(config, transport)

  def witnessApiSurface: ApiSurface[Future] = TypesafeClient

  def witnessLower(carried: CIO[Int]): Future[Int] =
    TypesafeClient.lower(carried)

  def witnessLift(fa: Future[Int]): CIO[Int] =
    TypesafeClient.lift(fa)
}
