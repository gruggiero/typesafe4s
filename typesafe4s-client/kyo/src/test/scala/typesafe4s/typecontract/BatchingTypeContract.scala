package typesafe4s.typecontract

import kyo.{<, Abort, Async, Scope, Stream}

import typesafe4s.{AnswerSet, ConcurrencyBound, Entry, Evaluation, Noul, TypesafeClient}
import typesafe4s.client.{BatchedClient, Client}

// ============================================================================
// TYPED CONTRACT — spec: question-batching (change: add-typesafe4s-sdk,
// spec 8/8) — Kyo row.
//
// Gate 1 artifact, APPROVED. The row's published alias re-binds to its
// `BatchedClient` instantiation; `client.systemOneBatched` returns
// `Stream[Evaluation[AnswerSet], Abort[Throwable] & Async & Scope]`.
//
//   type TypesafeClient = BatchedClient[[A] =>> A < (Abort[Throwable] & Async), [A] =>> Stream[A, Abort[Throwable] & Async & Scope]]
//
// RING-8 AMENDMENT — the stream's pending set carries `Scope`: the
// session's release is registered via `Scope.ensure` with the enclosing
// `Scope.run`, so an early `take`/abandon still closes the worker set
// (Emit discards the suspended producer continuation — an in-line close
// would never run). Callers drain inside `Scope.run` — the Kyo-native
// shape for a resourceful stream.
//
// Promoted at Step 3 to typesafe4s-client/kyo/.../TypesafeClient.scala;
// this file remains as a living compile-time assertion.
// ============================================================================

private object BatchingTypeContract {

  def witnessAlias(
    client: TypesafeClient
  ): BatchedClient[[A] =>> A < (Abort[Throwable] & Async), [A] =>> Stream[A, Abort[Throwable] & Async & Scope]] =
    client

  def witnessIsAClient(client: TypesafeClient): Client[[A] =>> A < (Abort[Throwable] & Async)] = client

  def witnessStreamShape(client: TypesafeClient): Stream[Evaluation[AnswerSet], Abort[Throwable] & Async & Scope] =
    client.systemOneBatched("a state")(Seq("q" -> Noul(Entry.text("is it so?"))), ConcurrencyBound.default)
}
