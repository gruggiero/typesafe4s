package typesafe4s.typecontract

import scala.concurrent.Future

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import typesafe4s.{AnswerSet, ConcurrencyBound, Entry, Evaluation, Noul, TypesafeClient}
import typesafe4s.client.{BatchedClient, Client}

// ============================================================================
// TYPED CONTRACT — spec: question-batching (change: add-typesafe4s-sdk,
// spec 8/8) — Pekko row.
//
// Gate 1 artifact, APPROVED. The row's published alias re-binds to its
// `BatchedClient` instantiation; `client.systemOneBatched` returns
// `Source[Evaluation[AnswerSet], NotUsed]` — a lazy acquire + unfold +
// termination watch over the shared session triple.
//
//   type TypesafeClient = BatchedClient[Future, [A] =>> Source[A, NotUsed]]
//
// DECLARED DIVERGENCE (spec Ring-5 table): abandoning the stream cannot
// stop exchanges already issued on the Future carrier — the `close`
// signal still runs on termination, it simply cannot preempt.
//
// Promoted at Step 3 to typesafe4s-client/pekko/.../TypesafeClient.scala;
// this file remains as a living compile-time assertion.
// ============================================================================

private object BatchingTypeContract {

  def witnessAlias(client: TypesafeClient): BatchedClient[Future, [A] =>> Source[A, NotUsed]] = client

  def witnessIsAClient(client: TypesafeClient): Client[Future] = client

  def witnessStreamShape(client: TypesafeClient): Source[Evaluation[AnswerSet], NotUsed] =
    client.systemOneBatched("a state")(Seq("q" -> Noul(Entry.text("is it so?"))), ConcurrencyBound.default)
}
