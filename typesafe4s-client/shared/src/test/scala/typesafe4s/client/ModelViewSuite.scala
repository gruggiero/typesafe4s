package typesafe4s.client

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{ApiKey, Entry, HttpResponse, Noul, RetryPolicy}
import typesafe4s.client.internal.{ClientCio, ManualClock, StandInTransport}
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// #25 — `withModel` is a view of the same client: only the request's model
// changes; the transport, retry policy, allowance and headers are shared,
// and the client that built the transport is the one that releases it.
// ============================================================================
final class ModelViewSuite extends FunSuite {

  @unused private given ExecutionContext = munitExecutionContext

  private val config = TypesafeConfig(
    apiKey = ApiKey.of("view-key"),
    baseUrl = "https://unit.test",
    model = "jev-latest",
    allowance = 30.seconds,
    retryPolicy = RetryPolicy.of(attempts = 1, initialWait = Duration.Zero, jitter = 0.0, totalBound = None),
    headers = List("x-tenant" -> "view"),
    logLevel = LogLevel.Error
  )

  private def answeredBy(model: String) =
    HttpResponse(200, Nil, s"""{"model":"$model","answers":{"q":{"type":"noul","noul":0.5}},"usage":{}}""")

  private val question = Seq("q" -> Noul(Entry.text("ok?")))

  private def modelOf(body: Option[String]): Option[String] =
    body.flatMap(b => """"model":"([^"]*)"""".r.findFirstMatchIn(b).map(_.group(1)))

  test("the view sends its model and the client keeps the configured one, over one transport") {
    val program = for {
      clock     <- ManualClock.init
      transport <- StandInTransport.init(List(Step.Answer(answeredBy("jev-cheap"))), Step.Answer(answeredBy("jev-latest")))
      client     = new ClientCio(config, transport, clock)
      cheap     <- client.withModel("jev-cheap").systemOneDynamic("subject")(question)
      latest    <- client.systemOneDynamic("subject")(question)
      requests  <- transport.requests
    } yield {
      assertEquals(requests.map(r => modelOf(r.body)), Vector(Some("jev-cheap"), Some("jev-latest")))
      assertEquals(requests.map(_.headers.filter(_._1 == "x-tenant")), Vector(List("x-tenant" -> "view"), List("x-tenant" -> "view")))
      assertEquals((cheap.model, latest.model), ("jev-cheap", "jev-latest"))
    }
    program.unsafeRun
  }

  test("the view retries under the client's policy") {
    val program = for {
      clock     <- ManualClock.init
      transport <- StandInTransport.init(List(Step.Answer(HttpResponse(503, Nil, "down"))), Step.Answer(answeredBy("jev-cheap")))
      client     = new ClientCio(config, transport, clock)
      result    <- client.withModel("jev-cheap").systemOneDynamic("subject")(question)
      begun     <- transport.begun.get
    } yield {
      assertEquals(begun, 2)
      assertEquals(result.model, "jev-cheap")
    }
    program.unsafeRun
  }

  test("closing a view releases nothing; closing the client releases the transport once") {
    val program = for {
      clock     <- ManualClock.init
      transport <- StandInTransport.init(Nil, Step.Answer(answeredBy("m")))
      client     = new ClientCio(config, transport, clock)
      _         <- client.withModel("a").withModel("b").close
      afterView <- transport.released.get
      _         <- client.close
      afterBoth <- transport.released.get
    } yield assertEquals((afterView, afterBoth), (0, 1))
    program.unsafeRun
  }

  test("a view through the shared adapter asks its model with the same conversions") {
    type Toy[A] = () => A
    val program = for {
      clock     <- ManualClock.init
      transport <- StandInTransport.init(Nil, Step.Answer(answeredBy("jev-cheap")))
      toy        = new LoweredClient[Toy](new ClientCio(config, transport, clock)) {
                     protected def lower[A](c: CIO[A]): Toy[A] = () => scala.concurrent.Await.result(c.unsafeRun, 30.seconds)
                     protected def lift[A](t: Toy[A]): CIO[A]  = CIO.defer(t())
                   }
      result    <- CIO.defer(toy.withModel("jev-cheap").systemOneDynamic("subject")(question)())
      requests  <- transport.requests
    } yield {
      assertEquals(modelOf(requests.head.body), Some("jev-cheap"))
      assertEquals(result.model, "jev-cheap")
    }
    program.unsafeRun
  }
}
