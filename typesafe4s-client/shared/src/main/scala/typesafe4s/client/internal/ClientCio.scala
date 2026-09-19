package typesafe4s.client.internal

import scala.NamedTuple.NamedTuple

import kyo.compat.*

import typesafe4s.{
  AnswerOf,
  AnswerSet,
  ConcurrencyBound,
  Evaluation,
  HttpMethod,
  HttpRequest,
  Model,
  Models,
  QuestionSet,
  RequestId,
  StateEncoder,
  SystemOne,
  TypesafeException
}
import typesafe4s.client.{Client, Transport, TypesafeConfig}

// ============================================================================
// spec: effect-portability — the carrier client the facades wrap. A
// locally-invalid question set fails before any request is formed; a valid
// one flows through `Ask.run`, and the typed surface reassembles the
// answer positionally through `SystemOne.assembleTyped` — the same
// assembly `SystemOne.decodeTyped` uses, so the two surfaces cannot drift.
// ============================================================================
final private[typesafe4s] class ClientCio(
  val config: TypesafeConfig,
  val transport: Transport,
  val clock: Clock
) extends Client[CIO] {

  def askDynamic[A](
    state: A,
    questions: Either[TypesafeException.InvalidQuestion, QuestionSet]
  )(using enc: StateEncoder[A]): CIO[Evaluation[AnswerSet]] =
    questions.fold(e => CIO.fail(e), qs => Ask.run(config, transport, clock)(state, qs))

  def askTyped[A, N <: Tuple, V <: Tuple](
    state: A,
    questions: Either[TypesafeException.InvalidQuestion, QuestionSet]
  )(using enc: StateEncoder[A]): CIO[Evaluation[NamedTuple[N, Tuple.Map[V, AnswerOf]]]] =
    questions.fold(
      e => CIO.fail(e),
      qs =>
        Ask.run(config, transport, clock)(state, qs).flatMap { ev =>
          SystemOne
            .assembleTyped[N, V](qs, ev.answers)
            .fold(
              e => CIO.fail(e),
              answers => CIO.value(ev.map(_ => answers))
            )
        }
    )

  // spec: question-batching — the carrier-level batched seam: same
  // Either-taking convention as the ask seams — a locally-invalid set
  // fails before any request is formed.
  def askBatched[A](
    state: A,
    questions: Either[TypesafeException.InvalidQuestion, QuestionSet],
    concurrency: ConcurrencyBound
  )(using enc: StateEncoder[A]): CIO[BatchRun] =
    BatchRun.start(config, transport, clock, concurrency)(state, questions)

  // spec: client-configuration — the explicit release: what the client
  // holds is the transport; releasing it is the transport's `release`.
  def close: CIO[Unit] = transport.release

  // spec: client-configuration — Evaluation/listJudges: `GET /v1/models`
  // through the same exchange pipeline, strictly decoded.
  def listModels: CIO[List[Model]] = {
    val request = HttpRequest.exchange(
      HttpMethod.Get,
      s"${config.baseUrl}/v1/models",
      None,
      config.apiKey,
      configured = config.headers
    )
    Exchange
      .run(transport, config.retryPolicy, clock, config.allowance)(request)
      .flatMap { response =>
        val rid = Exchange
          .requestId(response)
          .flatMap(r => RequestId.fromHeader(request.redact(RequestId.value(r))))
        Models
          .decodeResponse(rid, response.body)
          .fold(
            e => CIO.fail(e.copy(detail = request.redact(e.detail))),
            models => CIO.value(models)
          )
      }
  }
}
