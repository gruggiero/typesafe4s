package typesafe4s.client.typecontract

import kyo.compat.*

import typesafe4s.{AnswerSet, ConcurrencyBound, Evaluation, QuestionSet, StateEncoder}
import typesafe4s.client.{BatchedClient, Client, Transport, TypesafeConfig}
import typesafe4s.client.internal.{BatchRun, ClientCio, Clock}

// ============================================================================
// TYPED CONTRACT — spec: question-batching (change: add-typesafe4s-sdk,
// spec 8/8) — SHARED half.
//
// Gate 1 artifact, APPROVED. The declarations were promoted to
// typesafe4s-client/shared/src/main/scala/typesafe4s/client/BatchedClient.scala
// and .../client/internal/BatchRun.scala at Step 3; this file remains as a
// living compile-time assertion of the approved surface.
//
// Approved surface (Gate 1):
//   * `BatchedClient[F, S]` — ONE declaration of the batched call,
//     instantiated per row with the ecosystem's stream constructor as `S`
//     (the five row contracts pin each alias). `Client[F]` gains nothing —
//     a stream type cannot be named through `F`, and no carrier type may
//     appear in a published signature.
//   * `BatchRun` — the batched exchange as a SESSION over the carrier:
//     `start` acquires the worker set (bounded by `CMeter` on
//     `concurrency`, results flowing through a `CChannel`), `next` pulls
//     one batch's `Evaluation[AnswerSet]` — `None` when every batch is
//     accounted for — and `close` signals abandonment (pekko's Future
//     carrier cannot preempt in-flight exchanges — the declared
//     divergence). A session triple, not a bare `CStream`: the carrier
//     stream has no acquire/release primitive, so early abandonment could
//     not reach the workers.
//   * `ClientCio.askBatched` — the Either-taking carrier seam;
//     `LoweredClient.underlying` narrows `Client[CIO]` -> `ClientCio` and
//     `startBatched` forwards (both package-private — invisible to callers).
//
// Approved design decisions: Q1 arrival-order emission · Q2 dynamic-only
// surface · Q3 ConcurrencyBound in core, default 4 · Q4 approximateTokens
// the only numeric view.
// ============================================================================

private object QuestionBatchingTypeContract {

  // the batched client IS a Client — the extension adds, never subtracts
  def witnessIsAClient[F[_], S[_]](client: BatchedClient[F, S]): Client[F] = client

  // the published batched signature — one stream element per batch
  def witnessSignature[F[_], S[_], A](client: BatchedClient[F, S], state: A, qs: QuestionSet)(using enc: StateEncoder[A]): S[Evaluation[AnswerSet]] =
    client.systemOneBatched(state)(qs.entries, ConcurrencyBound.default)

  // the session triple
  def witnessNext(run: BatchRun): CIO[Option[Evaluation[AnswerSet]]] = run.next
  def witnessClose(run: BatchRun): CIO[Unit]                         = run.close

  def witnessStart[A](
    config: TypesafeConfig,
    transport: Transport,
    clock: Clock,
    state: A,
    qs: QuestionSet
  )(using enc: StateEncoder[A]): CIO[BatchRun] =
    BatchRun.start(config, transport, clock, ConcurrencyBound.default)(state, Right(qs))

  // the carrier seam — Either-taking like the ask seams
  def witnessAskBatched[A](client: ClientCio, state: A, qs: QuestionSet)(using enc: StateEncoder[A]): CIO[BatchRun] =
    client.askBatched(state, Right(qs), ConcurrencyBound.default)

  // `Client[F]` is untouched — the carrier stays unmet by every published
  // member (no `BatchRun`, no `CStream`, no `CIO` in a `Client[F]` type).
  def witnessClientClean[F[_]](client: Client[F]): Client[F] = client
}
