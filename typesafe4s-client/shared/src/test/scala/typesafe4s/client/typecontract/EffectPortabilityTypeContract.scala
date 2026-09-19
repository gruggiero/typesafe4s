package typesafe4s.client.typecontract

import scala.NamedTuple.NamedTuple
import scala.concurrent.duration.FiniteDuration

import kyo.compat.*

import typesafe4s.{
  AnswerOf,
  AnswerSet,
  ApiKey,
  Evaluation,
  Noul,
  NoulAnswer,
  Question,
  QuestionSet,
  RequestId,
  RetryPolicy,
  StateEncoder,
  TypesafeException,
  Usage
}
import typesafe4s.client.{ApiSurface, Client, LogLevel, LoweredClient, Transport, TypesafeConfig}

// ============================================================================
// TYPED CONTRACT — spec: effect-portability (change: add-typesafe4s-sdk, spec 6/8)
//
// Gate 1 artifact, APPROVED. The declarations were promoted to
// typesafe4s-core/src/main/scala/typesafe4s/Evaluation.scala and
// typesafe4s-client/shared/src/main/scala/typesafe4s/client/{Client,
// LoweredClient, ApiSurface, TypesafeConfig, LogLevel}.scala +
// internal/{Ask, ClientCio}.scala at Step 3; this file remains as a living
// compile-time assertion of the approved surface — if the implementation
// drifts from it, this file stops compiling.
//
// Approved surface (Gate 1):
//   typesafe4s.Evaluation[A] — final case class { answers, model, usage,
//     requestId } — the ask result product; `map` carries the metadata.
//   typesafe4s.client.Client[F] — published ops `final def systemOneDynamic`
//     and `final inline def systemOne`; seams `askDynamic`/`askTyped` public
//     ONLY because the inline body must reach them at the expansion site.
//   object Client — private[typesafe4s] def cio(config, transport): Client[CIO]
//   private[typesafe4s] abstract class LoweredClient[F](underlying: Client[CIO])
//     — exactly two abstract conversions, `lower`/`lift`; every operation
//     `final`, derived by lowering.
//   typesafe4s.client.ApiSurface[F] — the completeness check every facade
//     `object TypesafeClient` implements: of(config), of(config, transport).
//   typesafe4s.client.TypesafeConfig — operand shape only (spec 7 owns
//     resolution/redaction); LogLevel — operand member.
//   private[typesafe4s] internal.{Ask, ClientCio} — the ask pipeline and the
//     carrier client, written once against the carrier.
//
// Approved design decisions:
//   E1 `Evaluation[A]` is the ask result product — answers plus model,
//      usage, requestId — rather than a bare AnswerSet/tuple: trace and
//      cost are part of what an ask returns.
//   E2 The two published operations are `final` on the trait; the only
//      overridable seams take the pre-validated question set as an Either,
//      so a locally-invalid set fails before any request is formed.
//   E3 `LoweredClient`, `Client.cio`, `ClientCio`, `Ask` are
//      `private[typesafe4s]` — "a caller never meets the carrier": no
//      caller-reachable signature produces or mentions `CIO`.
//   E4 `ApiSurface[F]` IS the compile-time completeness check: a forgotten
//      operation stays abstract and fails the row compile naming it.
//   E5 `systemOne`'s `inline` is REQUIRED, not cosmetic: `fromNamedTuple`'s
//      empty-set refusal must fire at the caller's compile, so the call
//      must expand at the use site.
// ============================================================================

private object EffectPortabilityTypeContract {

  // surface witnesses — stop compiling if the promoted surface drifts.
  // Defs, not vals: nothing evaluates at class-init.

  def witnessEvaluation(e: Evaluation[AnswerSet]): (AnswerSet, String, Usage, Option[RequestId]) =
    (e.answers, e.model, e.usage, e.requestId)

  def witnessEvaluationMap(e: Evaluation[AnswerSet]): Evaluation[Int] =
    e.map(_ => 1)

  def witnessSystemOneDynamic[F[_]](
    client: Client[F],
    state: String,
    questions: IterableOnce[(String, Question[?])]
  ): F[Evaluation[AnswerSet]] =
    client.systemOneDynamic(state)(questions)

  // `systemOne` expands `fromNamedTuple` at the use site — the tuple must
  // be concrete for `Tuple.Size` to reduce, so the witness fixes one.
  def witnessSystemOne[F[_]](
    client: Client[F],
    state: String,
    question: Noul
  ): F[Evaluation[(a: NoulAnswer)]] =
    client.systemOne(state)((a = question))

  def witnessAskDynamic[F[_]](
    client: Client[F],
    state: String,
    questions: Either[TypesafeException.InvalidQuestion, QuestionSet]
  ): F[Evaluation[AnswerSet]] =
    client.askDynamic(state, questions)

  def witnessAskTyped[F[_], N <: Tuple, V <: Tuple](
    client: Client[F],
    state: String,
    questions: Either[TypesafeException.InvalidQuestion, QuestionSet]
  ): F[Evaluation[NamedTuple[N, Tuple.Map[V, AnswerOf]]]] =
    client.askTyped(state, questions)

  def witnessCio(config: TypesafeConfig, transport: Transport): Client[CIO] =
    Client.cio(config, transport)

  // constructing an anonymous LoweredClient witnesses the adapter's exact
  // shape: one constructor arg (the concrete carrier client — narrowed to
  // ClientCio at spec 8 so the batched seam is reachable) and exactly two
  // abstract conversions — a third abstract member would leave this
  // uncompilable.
  def witnessLowered[F[_]](underlying: typesafe4s.client.internal.ClientCio): LoweredClient[F] =
    new LoweredClient[F](underlying) {
      protected def lower[A](carried: CIO[A]): F[A] = ???
      protected def lift[A](fa: F[A]): CIO[A]       = ???
    }

  def witnessApiSurface[F[_]](surface: ApiSurface[F], config: TypesafeConfig, transport: Transport): (Client[F], Client[F]) =
    (surface.of(config), surface.of(config, transport))

  def witnessConfig(
    config: TypesafeConfig
  ): (ApiKey, String, String, FiniteDuration, RetryPolicy, List[(String, String)], LogLevel) =
    (config.apiKey, config.baseUrl, config.model, config.allowance, config.retryPolicy, config.headers, config.logLevel)
}
