package typesafe4s.client

import scala.NamedTuple.NamedTuple

import kyo.compat.*

import typesafe4s.{AnswerOf, AnswerSet, Evaluation, Model, Question, QuestionSet, StateEncoder, TypesafeException}
import typesafe4s.client.internal.ClientCio

// ============================================================================
// spec: effect-portability — Implementation Anchors: `Client[F]`
// The shared client, written once against the caller's effect F. A caller
// sees only their ecosystem's type through the facade's alias; the carrier
// appears in no member's signature.
//
// The two `systemOne*` calls are the published operations — both `final`,
// both delegating to the `askDynamic`/`askTyped` seams, which take the
// pre-validated question set as an Either so a locally-invalid set fails
// before any request is formed. The seams are public ONLY because
// `systemOne`'s inline body must reach them at the caller's expansion site
// — a protected member would not compile there.
// ============================================================================
trait Client[F[_]] {

  // the dynamic surface: a question set assembled at run time. Validation
  // rides through the seam's Either — an invalid set fails before any
  // request is formed.
  final def systemOneDynamic[A](state: A)(
    questions: IterableOnce[(String, Question[?])]
  )(using enc: StateEncoder[A]): F[Evaluation[AnswerSet]] =
    askDynamic(state, QuestionSet.validated(questions))

  // the typed surface: wire keys come from the field names of the named
  // tuple; the answer is assembled positionally into `AnswerOf`-typed
  // fields. An empty named tuple refuses to compile inside
  // `fromNamedTuple`.
  final inline def systemOne[A, N <: Tuple, V <: Tuple](state: A)(
    questions: NamedTuple[N, V]
  )(using enc: StateEncoder[A], ev: Tuple.Union[V] <:< Question[?]): F[Evaluation[NamedTuple[N, Tuple.Map[V, AnswerOf]]]] =
    askTyped(state, QuestionSet.fromNamedTuple(questions))

  def askDynamic[A](
    state: A,
    questions: Either[TypesafeException.InvalidQuestion, QuestionSet]
  )(using enc: StateEncoder[A]): F[Evaluation[AnswerSet]]

  def askTyped[A, N <: Tuple, V <: Tuple](
    state: A,
    questions: Either[TypesafeException.InvalidQuestion, QuestionSet]
  )(using enc: StateEncoder[A]): F[Evaluation[NamedTuple[N, Tuple.Map[V, AnswerOf]]]]

  // spec: client-configuration — Scenario: Construction outside a scope:
  // the explicit release for a client built without a scoped constructor.
  // Releases what the client holds — the transport's `release`.
  def close: F[Unit]

  // spec: client-configuration — Evaluation/listJudges: the models the
  // account may ask. NO local judgement of a name — a configured model
  // absent from the listing is still sent as given.
  def listModels: F[List[Model]]
}

object Client {

  // the carrier client every facade wraps — package-private so no published
  // operation's type mentions the carrier (Scenario: A caller never meets
  // the carrier). The return type is the concrete carrier client so the
  // batched seam is reachable (spec: question-batching).
  private[typesafe4s] def cio(config: TypesafeConfig, transport: Transport): ClientCio =
    new ClientCio(config, transport, typesafe4s.client.internal.Clock.system)

  // spec: client-configuration — the carrier-level scoped construction:
  // `acquireReleaseWith(acquire)(release = _.close)(use)` — release runs on
  // success, failure and abandonment of `use`, on every row the carrier
  // runs on. Each row's idiom lowers this same bracket.
  private[typesafe4s] def scoped[A](config: TypesafeConfig, transport: Transport)(
    use: Client[CIO] => CIO[A]
  ): CIO[A] =
    CIO.acquireReleaseWith(
      CIO.value(cio(config, transport))
    )(
      _.close
    )(
      use
    )
}
