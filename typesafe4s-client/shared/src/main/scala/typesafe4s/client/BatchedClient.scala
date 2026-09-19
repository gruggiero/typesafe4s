package typesafe4s.client

import typesafe4s.{AnswerSet, ConcurrencyBound, Evaluation, Question, QuestionSet, StateEncoder, TypesafeException}

// ============================================================================
// spec: question-batching — Implementation Anchors: "Streaming surface —
// per-row facade".
//
// ONE declaration of the batched call, instantiated per row with the
// ecosystem's stream constructor as `S` (ZStream, fs2.Stream, Ox Flow,
// kyo Stream, Pekko Source). `Client[F]` gains nothing — a stream type
// cannot be named through `F`, and no carrier type may appear in a
// published signature; each row's `TypesafeClient` alias re-binds to its
// `BatchedClient` instantiation.
//
// DYNAMIC-ONLY: a batch carries a subset of the question set, so a
// per-batch named tuple could never assemble the caller's full AnswerOf
// product — the emitted element is `Evaluation[AnswerSet]`.
// ============================================================================
trait BatchedClient[F[_], S[_]] extends Client[F] {

  // spec: question-batching — Requirement: A set too large for one exchange
  // is divided and streamed. One element per batch, emitted as that batch
  // resolves; a fitting set emits exactly one. An invalid or unfittable
  // question set fails the stream before any exchange; a batch that fails
  // after retries exhausts fails the stream with that failure, and answers
  // already emitted remain usable.
  final def systemOneBatched[A](state: A)(
    questions: IterableOnce[(String, Question[?])],
    concurrency: ConcurrencyBound = ConcurrencyBound.default
  )(using enc: StateEncoder[A]): S[Evaluation[AnswerSet]] =
    batched(state, QuestionSet.validated(questions), concurrency)

  // the per-row seam — same convention as askDynamic/askTyped: takes the
  // pre-validated set as an Either so a locally-invalid set fails before
  // any request is formed.
  def batched[A](
    state: A,
    questions: Either[TypesafeException.InvalidQuestion, QuestionSet],
    concurrency: ConcurrencyBound
  )(using enc: StateEncoder[A]): S[Evaluation[AnswerSet]]
}
