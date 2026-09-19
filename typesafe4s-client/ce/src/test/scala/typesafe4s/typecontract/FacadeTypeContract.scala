package typesafe4s.typecontract

import cats.effect.IO
import kyo.compat.CIO

import typesafe4s.TypesafeClient
import typesafe4s.client.{ApiSurface, Client, Transport, TypesafeConfig}

// ============================================================================
// TYPED CONTRACT — spec: effect-portability (change: add-typesafe4s-sdk, spec 6/8)
// Facade contract — Cats Effect row.
//
// Gate 1 artifact, APPROVED. The declarations were promoted to
// typesafe4s-client/ce/src/main/scala/typesafe4s/TypesafeClient.scala at
// Step 3; this file remains as a living compile-time assertion.
//
// Approved surface:
//   type TypesafeClient = Client[IO]
//   object TypesafeClient extends ApiSurface[IO]
//   private[typesafe4s] lower/lift — the two conversions.
// ============================================================================

private object FacadeTypeContract {

  def witnessAlias(client: TypesafeClient): Client[IO] = client

  def witnessOf(config: TypesafeConfig): TypesafeClient =
    TypesafeClient.of(config)

  def witnessOfTransport(config: TypesafeConfig, transport: Transport): TypesafeClient =
    TypesafeClient.of(config, transport)

  def witnessApiSurface: ApiSurface[IO] = TypesafeClient

  def witnessLower(carried: CIO[Int]): IO[Int] =
    TypesafeClient.lower(carried)

  def witnessLift(fa: IO[Int]): CIO[Int] =
    TypesafeClient.lift(fa)
}
