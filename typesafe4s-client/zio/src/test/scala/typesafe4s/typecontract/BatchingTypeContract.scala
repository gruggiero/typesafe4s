package typesafe4s.typecontract

import zio.IO
import zio.stream.ZStream

import typesafe4s.{AnswerSet, ConcurrencyBound, Entry, Evaluation, Noul, TypesafeClient, TypesafeException}
import typesafe4s.client.{BatchedClient, Client}

// ============================================================================
// TYPED CONTRACT — spec: question-batching (change: add-typesafe4s-sdk,
// spec 8/8) — ZIO row.
//
// Gate 1 artifact, APPROVED. The row's published alias re-binds from
// `Client[IO[TypesafeException, *]]` to its `BatchedClient` instantiation,
// so `client.systemOneBatched` returns this ecosystem's stream:
// `ZStream[Any, TypesafeException, Evaluation[AnswerSet]]` — the typed
// failure channel holds in the stream too.
//
//   type TypesafeClient = BatchedClient[IO[TypesafeException, *], [A] =>> ZStream[Any, TypesafeException, A]]
//
// Promoted at Step 3 to typesafe4s-client/zio/.../TypesafeClient.scala;
// this file remains as a living compile-time assertion.
// ============================================================================

private object BatchingTypeContract {

  // the alias is the shared batched surface instantiated for this row —
  // and still a Client
  def witnessAlias(client: TypesafeClient): BatchedClient[IO[TypesafeException, *], [A] =>> ZStream[Any, TypesafeException, A]] =
    client

  def witnessIsAClient(client: TypesafeClient): Client[IO[TypesafeException, *]] = client

  // the batched call produces a typed-error ZStream of per-batch
  // evaluations; one element per batch
  def witnessStreamShape(client: TypesafeClient): ZStream[Any, TypesafeException, Evaluation[AnswerSet]] =
    client.systemOneBatched("a state")(Seq("q" -> Noul(Entry.text("is it so?"))), ConcurrencyBound.default)
}
