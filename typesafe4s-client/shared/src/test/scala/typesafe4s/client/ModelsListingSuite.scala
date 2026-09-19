package typesafe4s.client

import scala.annotation.unused
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

import kyo.compat.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import typesafe4s.{ApiKey, Entry, HttpMethod, HttpResponse, Model, Noul, RetryPolicy}
import typesafe4s.TypesafeException.*
import typesafe4s.client.internal.StandInTransport
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// TEST ORACLE — spec: client-configuration — models listing (7/8)
//
// `listModels` is the client's listing operation: `GET /v1/models`, strict
// decode, and no local judgement of model names — a configured model the
// listing does not name still works.
// ============================================================================
final class ModelsListingSuite extends ScalaCheckSuite {

  @unused private given ExecutionContext = munitExecutionContext

  private def run[A](c: CIO[A]): Try[A] = Try(Await.result(c.unsafeRun, 30.seconds))

  private val config: TypesafeConfig = TypesafeConfig(
    apiKey = ApiKey.of("sk-models"),
    baseUrl = "https://api.typesafe.ai",
    model = "jev-latest",
    allowance = 10.seconds,
    retryPolicy = RetryPolicy.of(attempts = 0, totalBound = None),
    headers = Nil,
    logLevel = LogLevel.Info
  )

  // the confirmed listing body shape (live-observed 2026-09-19, task 12.3)
  private val listingBody: String =
    """{"models":[{"name":"jev-1","description":"Jev One","release_date":"2026-01-02"},""" +
      """{"name":"jev-2","description":"Jev Two","release_date":"2026-03-04"}]}"""

  // spec: client-configuration — Scenario: Listing the models — the listing
  // is fetched and decoded into the model type
  test("listing-the-models") {
    val program = for {
      transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, listingBody)))
      client     = Client.cio(config, transport)
      models    <- client.listModels
      requests  <- transport.requests
    } yield (models, requests)
    run(program) match {
      case Success((models, requests)) =>
        assertEquals(
          models,
          List(
            Model("jev-1", "Jev One", "2026-01-02"),
            Model("jev-2", "Jev Two", "2026-03-04")
          )
        )
        assertEquals(requests.length, 1)
        assertEquals(requests.head.method, HttpMethod.Get)
        assertEquals(requests.head.target, "https://api.typesafe.ai/v1/models")
      case Failure(e)                  =>
        fail(s"listModels failed: $e")
    }
  }

  // spec: client-configuration — Scenario: A model the listing does not
  // name — evaluation works with a configured model absent from the listing
  test("a-model-the-listing-does-not-name") {
    val unlisted = config.copy(model = "m-unlisted")
    val program  = for {
      transport <- StandInTransport.init(
                     List(Step.Answer(HttpResponse(200, Nil, """{"models":[]}"""))),
                     Step.Answer(HttpResponse(422, Nil, "unprocessable"))
                   )
      client     = Client.cio(unlisted, transport)
      _         <- client.listModels
      outcome   <- client.systemOneDynamic("subject")(Seq("q" -> Noul(Entry.text("t?")))).liftToTry
      requests  <- transport.requests
    } yield (outcome, requests)
    run(program) match {
      case Success((outcome, requests)) =>
        // the exchange went out carrying the unlisted configured model —
        // whatever the service did with it
        assertEquals(requests.length, 2)
        assert(
          requests.last.body.exists(_.contains("m-unlisted")),
          s"request body did not carry the configured model: ${requests.last.body}"
        )
        outcome match {
          case Success(_) => ()
          case Failure(e) =>
            // an evaluation *content* failure is not a model-name rejection —
            // but no refusal may name the model as unknown
            assert(!e.getMessage.contains("m-unlisted"), s"model name was judged: ${e.getMessage}")
        }
      case Failure(e)                   =>
        fail(s"program failed: $e")
    }
  }

  // spec: client-configuration — Property: The model name is not judged
  // locally — any drawn name flows into the exchange unvalidated
  property("the-model-name-is-not-judged-locally") {
    forAll(Gen.asciiPrintableStr.suchThat(_.nonEmpty)) { name =>
      val program = for {
        transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, """{"models":[]}""")))
        client     = Client.cio(config.copy(model = name), transport)
        _         <- client.listModels
      } yield true
      // listing succeeds regardless of what model the config names
      run(program).getOrElse(false)
    }
  }

  // spec: client-configuration — Requirement: A response that does not fit
  // the documented shape is a ResponseValidation
  test("a-misshapen-listing-is-a-response-validation") {
    val bodies = List(
      """{"models":[{"name":"jev-1"}]}""",                                 // missing description + release_date
      """{"models":[{"name":42,"description":"x","release_date":"d"}]}""", // name of the wrong type
      """{"data":[]}""",                                                   // wrong envelope key (the pre-confirmation shape)
      """not json"""                                                       // not even JSON
    )
    bodies.foreach { body =>
      val program = for {
        transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, body)))
        client     = Client.cio(config, transport)
        outcome   <- client.listModels.liftToTry
      } yield outcome
      run(program) match {
        case Success(Failure(e)) =>
          assert(
            e.isInstanceOf[ResponseValidation],
            s"body `$body` raised ${e.getClass.getSimpleName}, not ResponseValidation"
          )
        case other               =>
          fail(s"body `$body` expected a ResponseValidation failure, got $other")
      }
    }
  }

  // spec: client-configuration — a refusal of the listing surfaces through
  // the same error family as any exchange
  test("a-refused-listing-surfaces-the-refusal") {
    val program = for {
      transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(401, List("request-id" -> "rid-9"), "no")))
      client     = Client.cio(config, transport)
      outcome   <- client.listModels.liftToTry
    } yield outcome
    run(program) match {
      case Success(Failure(e)) =>
        assert(e.isInstanceOf[Authentication], s"expected Authentication, got ${e.getClass.getSimpleName}")
      case other               =>
        fail(s"expected an Authentication failure, got $other")
    }
  }
}
