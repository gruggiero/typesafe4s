package typesafe4s.typecontract

import cats.effect.IO
import fs2.Stream

import typesafe4s.{AnswerSet, ConcurrencyBound, Entry, Evaluation, Noul, TypesafeClient}
import typesafe4s.client.{BatchedClient, Client}

// ============================================================================
// TYPED CONTRACT — spec: question-batching (change: add-typesafe4s-sdk,
// spec 8/8) — Cats Effect row.
//
// Gate 1 artifact, APPROVED. The row's published alias re-binds to its
// `BatchedClient` instantiation; `client.systemOneBatched` returns
// `fs2.Stream[IO, Evaluation[AnswerSet]]` — failures ride IO's throwable
// channel.
//
//   type TypesafeClient = BatchedClient[IO, [A] =>> fs2.Stream[IO, A]]
//
// Promoted at Step 3 to typesafe4s-client/ce/.../TypesafeClient.scala;
// this file remains as a living compile-time assertion.
// ============================================================================

private object BatchingTypeContract {

  def witnessAlias(client: TypesafeClient): BatchedClient[IO, [A] =>> Stream[IO, A]] = client

  def witnessIsAClient(client: TypesafeClient): Client[IO] = client

  def witnessStreamShape(client: TypesafeClient): Stream[IO, Evaluation[AnswerSet]] =
    client.systemOneBatched("a state")(Seq("q" -> Noul(Entry.text("is it so?"))), ConcurrencyBound.default)
}
