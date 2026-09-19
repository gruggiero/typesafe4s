package typesafe4s.examples

import scala.collection.immutable.ListMap
import scala.concurrent.duration.*

import kyo.compat.CIO

import typesafe4s.{AnswerSet, ApiKey, Choice, Entry, Evaluation, HttpRequest, HttpResponse, Noul, RetryPolicy}
import typesafe4s.client.{LogLevel, Transport, TypesafeConfig}

// ============================================================================
// spec: release-readiness — shared demo fixtures for the per-row examples.
//
// The examples run WITHOUT a credential: when `TYPESAFE_API_KEY` is absent
// they exchange against `stub`, which answers a canned System One response
// through the real request/response pipeline — rendering, strict decoding,
// usage and answer assembly are all exercised end to end; only the network
// hop is replaced. With the variable set they run against the live service.
// ============================================================================
object Demo {

  // a real question set — the same pair every row's examples ask
  val state: String = "The battery died after one day of use."

  val questions: List[(String, typesafe4s.Question[?])] = List(
    "sentiment" -> Noul(Entry.text("Does this review express frustration?")),
    "route"     -> Choice[String](
      Entry.text("What should happen to this ticket?"),
      ListMap(
        "refund"   -> Entry.text("Offer the customer a refund"),
        "escalate" -> Entry.text("Escalate to a human agent")
      ),
      key => Option.when(Set("refund", "escalate").contains(key))(key)
    )
  )

  // the canned wire response the stub returns — a real System One body
  // covering both asked names
  val stubBody: String =
    """{"model":"jev-latest","answers":{"sentiment":{"type":"noul","noul":0.94},"route":{"type":"choice","choice":"refund","probabilities":{"refund":0.81,"escalate":0.19},"confidence":0.87}},"usage":{"input_tokens":52,"output_tokens":11}}"""

  // the stub exchange: any request gets the canned answer — enough for the
  // examples to run the full decode path without a credential
  val stub: Transport = new Transport {
    def exchange(request: HttpRequest): CIO[HttpResponse] =
      CIO.value(HttpResponse(200, List("x-typesafe-request-id" -> "demo-rid"), stubBody))
  }

  // live when the documented credential is present, the stub otherwise —
  // the Left names WHY (absent credential vs an invalid sibling setting)
  def resolve(): (TypesafeConfig, Transport) =
    TypesafeConfig.resolve() match {
      case Right(config) => (config, Transport.default)
      case Left(error)   =>
        println(s"live config unresolved ($error) — running against the stub exchange")
        (demoConfig, stub)
    }

  val demoConfig: TypesafeConfig = TypesafeConfig(
    apiKey = ApiKey.of("demo-key"),
    baseUrl = "http://localhost.invalid",
    model = "jev-latest",
    allowance = 10.seconds,
    retryPolicy = RetryPolicy.of(attempts = 0, totalBound = None),
    headers = Nil,
    logLevel = LogLevel.Error
  )

  def render(evaluation: Evaluation[AnswerSet]): String = {
    val answers = evaluation.answers.answers
      .map { case (name, answer) => s"  $name -> $answer" }
      .mkString("\n")
    s"model: ${evaluation.model}\nusage: ${evaluation.usage}\nanswers:\n$answers"
  }
}
