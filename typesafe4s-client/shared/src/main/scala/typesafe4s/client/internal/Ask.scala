package typesafe4s.client.internal

import kyo.compat.*

import typesafe4s.{AnswerSet, Evaluation, HttpMethod, HttpRequest, QuestionSet, RequestId, StateEncoder, SystemOne}
import typesafe4s.client.{Transport, TypesafeConfig}
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
    val observer = LoggingObserver.of(config)
    val request  = HttpRequest.exchange(
      HttpMethod.Post,
      s"${config.baseUrl}/v1/systemone",
      Some(Json.render(SystemOne.renderRequest(state, config.model, questions))),
      config.apiKey,
      configured = UserAgent.withSdk(config.headers)
    )
    Exchange
      .run(transport, config.retryPolicy, clock, config.allowance, observer)(request)
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
            e => {
              val scrubbed = e.copy(detail = request.redact(e.detail))
              Observe(observer.onFailure(request.method, Observe.path(request.target), scrubbed)).flatMap(_ => CIO.fail(scrubbed))
            },
            set =>
              Observe(observer.onEvaluation(set.model, set.usage, set.requestId))
                .map(_ => Evaluation(set, set.model, set.usage, set.requestId))
          )
      }
  }
}
