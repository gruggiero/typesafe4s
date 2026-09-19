package typesafe4s.typecontract

import ox.Ox
import ox.flow.Flow

import typesafe4s.{AnswerSet, ConcurrencyBound, Entry, Evaluation, Noul, TypesafeClient}
import typesafe4s.client.{BatchedClient, Client}

// ============================================================================
// TYPED CONTRACT — spec: question-batching (change: add-typesafe4s-sdk,
// spec 8/8) — Ox row.
//
// Gate 1 artifact, APPROVED. The row's published alias re-binds to its
// `BatchedClient` instantiation; `client.systemOneBatched` returns
// `Ox ?=> Flow[Evaluation[AnswerSet]]` — the Flow's session is acquired
// inside the caller's supervised scope, matching the row's context-passed
// effect.
//
//   type TypesafeClient = BatchedClient[[A] =>> Ox ?=> A, [A] =>> Ox ?=> Flow[A]]
//
// Promoted at Step 3 to typesafe4s-client/ox/.../TypesafeClient.scala;
// this file remains as a living compile-time assertion.
// ============================================================================

private object BatchingTypeContract {

  def witnessAlias(client: TypesafeClient): BatchedClient[[A] =>> Ox ?=> A, [A] =>> Ox ?=> Flow[A]] = client

  def witnessIsAClient(client: TypesafeClient): Client[[A] =>> Ox ?=> A] = client

  def witnessStreamShape(client: TypesafeClient): Ox ?=> Flow[Evaluation[AnswerSet]] =
    client.systemOneBatched("a state")(Seq("q" -> Noul(Entry.text("is it so?"))), ConcurrencyBound.default)
}
