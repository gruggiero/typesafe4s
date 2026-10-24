package typesafe4s.client.internal

import scala.util.Try

import kyo.compat.*

import typesafe4s.{AnswerSet, Evaluation, HttpMethod, HttpRequest, QuestionSet, RequestId, StateEncoder, SystemOne}
import typesafe4s.client.{ResponseCache, Transport, TypesafeConfig}
import typesafe4s.json.Json

// ============================================================================
// spec: effect-portability — the shared ask pipeline, expressed once over
// the carrier. Render → exchange-with-retry → decode → Evaluation.
// Every facade's client delegates here through `lower`; no ecosystem's
// sources restate it.
// ============================================================================
private[typesafe4s] object Ask {

  def run[A](
    config: TypesafeConfig,
    transport: Transport,
    clock: Clock
  )(
    state: A,
    questions: QuestionSet
  )(using enc: StateEncoder[A]): CIO[Evaluation[AnswerSet]] = {
    val body    = Json.render(SystemOne.renderRequest(state, config.model, questions))
    val request = HttpRequest.exchange(
      HttpMethod.Post,
      s"${config.baseUrl}/v1/systemone",
      Some(body),
      config.apiKey,
      configured = UserAgent.withSdk(config.headers)
    )
    val asked   = Exchange
      .run(transport, config.retryPolicy, clock, config.allowance)(request)
      .flatMap { response =>
        val rid = Exchange
          .requestId(response)
          .flatMap(r => RequestId.fromHeader(request.redact(RequestId.value(r))))
        SystemOne
          .decodeResponse(questions, rid, response.body)
          .fold(
            // a decode failure's detail can echo service-sent fragments —
            // the exchange's credential scrubs them before the member
            // stores them (spec: client-configuration — no failure
            // renders the secret)
            e => CIO.fail(e.copy(detail = request.redact(e.detail))),
            set => CIO.value(Evaluation(set, set.model, set.usage, set.requestId))
          )
      }
    // #49: a hit makes no request; only a success is stored; a cache that
    // throws is a miss and never fails the call
    config.cache match {
      case None        => asked
      case Some(cache) =>
        val key = ResponseCache.key(request.target, body)
        CIO.defer(Try(cache.get(key)).toOption.flatten).flatMap {
          case Some(hit) => CIO.value(hit)
          case None      => asked.flatMap(evaluation => CIO.defer(Try(cache.put(key, evaluation))).map(_ => evaluation))
        }
    }
  }
}
