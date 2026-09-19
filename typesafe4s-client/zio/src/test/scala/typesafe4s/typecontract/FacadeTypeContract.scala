package typesafe4s.typecontract

import kyo.compat.CIO
import zio.IO

import typesafe4s.{TypesafeClient, TypesafeException}
import typesafe4s.client.{ApiSurface, Client, Transport, TypesafeConfig}

// ============================================================================
// TYPED CONTRACT — spec: effect-portability (change: add-typesafe4s-sdk, spec 6/8)
// Facade contract — ZIO row.
//
// Gate 1 artifact, APPROVED. The declarations were promoted to
// typesafe4s-client/zio/src/main/scala/typesafe4s/TypesafeClient.scala at
// Step 3; this file remains as a living compile-time assertion — if the
// promoted facade drifts, this file stops compiling.
//
// Approved surface:
//   type TypesafeClient = Client[IO[TypesafeException, *]] — the typed
//     failure channel: the SDK's closed family via `refineToOrDie`.
//   object TypesafeClient extends ApiSurface[IO[TypesafeException, *]] —
//     of(config), of(config, transport).
//   private[typesafe4s] lower/lift — the two conversions; package-private,
//     never caller-reachable.
// ============================================================================

private object FacadeTypeContract {

  def witnessAlias(client: TypesafeClient): Client[IO[TypesafeException, *]] = client

  def witnessOf(config: TypesafeConfig): TypesafeClient =
    TypesafeClient.of(config)

  def witnessOfTransport(config: TypesafeConfig, transport: Transport): TypesafeClient =
    TypesafeClient.of(config, transport)

  def witnessApiSurface: ApiSurface[IO[TypesafeException, *]] = TypesafeClient

  def witnessLower(carried: CIO[Int]): IO[TypesafeException, Int] =
    TypesafeClient.lower(carried)

  def witnessLift(fa: IO[TypesafeException, Int]): CIO[Int] =
    TypesafeClient.lift(fa)
}
