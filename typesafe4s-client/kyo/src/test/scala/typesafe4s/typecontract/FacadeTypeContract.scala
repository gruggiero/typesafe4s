package typesafe4s.typecontract

import kyo.{<, Abort, Async}
import kyo.compat.CIO

import typesafe4s.TypesafeClient
import typesafe4s.client.{ApiSurface, Client, Transport, TypesafeConfig}

// ============================================================================
// TYPED CONTRACT — spec: effect-portability (change: add-typesafe4s-sdk, spec 6/8)
// Facade contract — Kyo row.
//
// Gate 1 artifact, APPROVED. The declarations were promoted to
// typesafe4s-client/kyo/src/main/scala/typesafe4s/TypesafeClient.scala at
// Step 3; this file remains as a living compile-time assertion.
//
// Approved surface:
//   type TypesafeClient = Client[[A] =>> A < (Abort[Throwable] & Async)]
//   object TypesafeClient extends ApiSurface[[A] =>> A < (Abort[Throwable] & Async)]
//   private[typesafe4s] lower/lift — the two conversions.
// ============================================================================

private object FacadeTypeContract {

  def witnessAlias(client: TypesafeClient): Client[[A] =>> A < (Abort[Throwable] & Async)] = client

  def witnessOf(config: TypesafeConfig): TypesafeClient =
    TypesafeClient.of(config)

  def witnessOfTransport(config: TypesafeConfig, transport: Transport): TypesafeClient =
    TypesafeClient.of(config, transport)

  def witnessApiSurface: ApiSurface[[A] =>> A < (Abort[Throwable] & Async)] = TypesafeClient

  def witnessLower(carried: CIO[Int]): Int < (Abort[Throwable] & Async) =
    TypesafeClient.lower(carried)

  def witnessLift(fa: Int < (Abort[Throwable] & Async)): CIO[Int] =
    TypesafeClient.lift(fa)
}
