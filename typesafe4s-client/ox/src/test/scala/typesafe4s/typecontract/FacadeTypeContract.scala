package typesafe4s.typecontract

import kyo.compat.CIO
import ox.Ox

import typesafe4s.TypesafeClient
import typesafe4s.client.{ApiSurface, Client, Transport, TypesafeConfig}

// ============================================================================
// TYPED CONTRACT — spec: effect-portability (change: add-typesafe4s-sdk, spec 6/8)
// Facade contract — Ox row.
//
// Gate 1 artifact, APPROVED. The declarations were promoted to
// typesafe4s-client/ox/src/main/scala/typesafe4s/TypesafeClient.scala at
// Step 3; this file remains as a living compile-time assertion.
//
// Approved surface:
//   type TypesafeClient = Client[[A] =>> Ox ?=> A] — context-passed effect.
//   object TypesafeClient extends ApiSurface[[A] =>> Ox ?=> A]
//   private[typesafe4s] lower/lift — the two conversions.
// ============================================================================

private object FacadeTypeContract {

  def witnessAlias(client: TypesafeClient): Client[[A] =>> Ox ?=> A] = client

  def witnessOf(config: TypesafeConfig): TypesafeClient =
    TypesafeClient.of(config)

  def witnessOfTransport(config: TypesafeConfig, transport: Transport): TypesafeClient =
    TypesafeClient.of(config, transport)

  def witnessApiSurface: ApiSurface[[A] =>> Ox ?=> A] = TypesafeClient

  def witnessLower(carried: CIO[Int]): Ox ?=> Int =
    TypesafeClient.lower(carried)

  def witnessLift(fa: Ox ?=> Int): CIO[Int] =
    TypesafeClient.lift(fa)
}
