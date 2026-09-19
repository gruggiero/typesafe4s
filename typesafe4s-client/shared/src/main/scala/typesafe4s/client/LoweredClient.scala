package typesafe4s.client

import scala.NamedTuple.NamedTuple

import kyo.compat.*

import typesafe4s.{AnswerOf, AnswerSet, ConcurrencyBound, Evaluation, Model, QuestionSet, StateEncoder, TypesafeException}
import typesafe4s.client.internal.{BatchRun, ClientCio}

// ============================================================================
// spec: effect-portability — Concepts Introduced: Facade —
// Implementation Anchors: `LoweredClient[F]`
//
// An ecosystem is adapted by supplying exactly TWO conversions —
// `lower` (carrier → the ecosystem's effect) and `lift` (back). Every
// operation is `final` here and derived by lowering the shared carrier
// client's result: no shared behaviour is restated per facade (Scenario:
// Each hard behaviour exists once).
//
// Package-private: the adapter is the SDK's own mechanism. Callers obtain
// a `Client[F]` through their facade's `of` — they never meet the carrier.
// ============================================================================
abstract private[typesafe4s] class LoweredClient[F[_]](underlying: ClientCio) extends Client[F] {

  protected def lower[A](carried: CIO[A]): F[A]
  protected def lift[A](fa: F[A]): CIO[A]

  final def askDynamic[A](
    state: A,
    questions: Either[TypesafeException.InvalidQuestion, QuestionSet]
  )(using enc: StateEncoder[A]): F[Evaluation[AnswerSet]] =
    lower(underlying.askDynamic(state, questions))

  final def askTyped[A, N <: Tuple, V <: Tuple](
    state: A,
    questions: Either[TypesafeException.InvalidQuestion, QuestionSet]
  )(using enc: StateEncoder[A]): F[Evaluation[NamedTuple[N, Tuple.Map[V, AnswerOf]]]] =
    lower(underlying.askTyped(state, questions))

  final def close: F[Unit] = lower(underlying.close)

  final def listModels: F[List[Model]] = lower(underlying.listModels)

  // spec: question-batching — the batched session on the carrier client,
  // forwarder so each row's `batched` seam can reach it. Returns the
  // carrier-level session; the row wraps `next`/`close` in its own
  // stream's bracket and unfold.
  final private[typesafe4s] def startBatched[A](
    state: A,
    questions: Either[TypesafeException.InvalidQuestion, QuestionSet],
    concurrency: ConcurrencyBound
  )(using enc: StateEncoder[A]): CIO[BatchRun] =
    underlying.askBatched(state, questions, concurrency)
}
