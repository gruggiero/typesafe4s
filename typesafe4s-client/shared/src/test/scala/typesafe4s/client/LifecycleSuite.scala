package typesafe4s.client

import scala.annotation.unused
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{ApiKey, HttpMethod, HttpRequest, HttpResponse, RetryPolicy}
import typesafe4s.client.internal.StandInTransport
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// TEST ORACLE — spec: client-configuration — lifecycle (7/8)
//
// A client releases what it owns. Scoped construction releases on success,
// on failure, and on abandonment; manual construction exposes `close`.
// ============================================================================
final class LifecycleSuite extends FunSuite {

  @unused private given ExecutionContext = munitExecutionContext

  private def run[A](c: CIO[A]): Try[A] = Try(Await.result(c.unsafeRun, 30.seconds))

  private val config: TypesafeConfig = TypesafeConfig(
    apiKey = ApiKey.of("sk-life"),
    baseUrl = "https://api.typesafe.ai",
    model = "jev-latest",
    allowance = 10.seconds,
    retryPolicy = RetryPolicy.of(attempts = 0, totalBound = None),
    headers = Nil,
    logLevel = LogLevel.Info
  )

  // spec: client-configuration — Scenario: Construction within a scope —
  // the client is usable inside the scope and released when it ends
  test("construction-within-a-scope") {
    val program = for {
      transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, """{"models":[]}""")))
      models    <- Client.scoped(config, transport)(_.listModels)
      released  <- transport.released.get
    } yield (models, released)
    run(program) match {
      case Success((models, released)) =>
        assert(models.isEmpty, s"unexpected models: $models")
        assertEquals(released, 1, "the owned transport was not released exactly once")
      case Failure(e)                  =>
        fail(s"scoped construction failed: $e")
    }
  }

  // spec: client-configuration — Scenario: A scope that ends in failure —
  // release still happens, and the failure propagates unchanged
  test("a-scope-that-ends-in-failure") {
    val boom    = new RuntimeException("use failed")
    val program = for {
      transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, "{}")))
      outcome   <- Client.scoped[Unit](config, transport)(_ => CIO.fail(boom)).liftToTry
      released  <- transport.released.get
    } yield (outcome, released)
    run(program) match {
      case Success((outcome, released)) =>
        outcome match {
          case Failure(e) => assertEquals(e, boom, "the use failure did not propagate unchanged")
          case other      => fail(s"expected the use failure, got $other")
        }
        assertEquals(released, 1, "the transport was not released on failure")
      case Failure(e)                   =>
        fail(s"scoped construction failed: $e")
    }
  }

  // spec: client-configuration — Scenario: Construction outside a scope —
  // the caller drives the lifecycle and closes explicitly
  test("construction-outside-a-scope") {
    val program = for {
      transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, """{"models":[]}""")))
      client     = Client.cio(config, transport)
      _         <- client.listModels
      _         <- client.close
      released  <- transport.released.get
    } yield released
    run(program) match {
      case Success(released) =>
        assertEquals(released, 1, "close did not release the owned transport")
      case Failure(e)        =>
        fail(s"manual construction failed: $e")
    }
  }

  // spec: client-configuration — Requirement: the default transport is
  // owned — releasing it shuts the underlying client down; a caller-supplied
  // transport is caller-owned and release is a no-op
  test("an-owned-jdk-transport-closes-its-client-on-release") {
    val transport = JdkHttpTransport()
    run(transport.release) match {
      case Failure(e) => fail(s"release failed: $e")
      case Success(_) =>
        // after release the underlying HttpClient is shut — an exchange
        // cannot even be attempted
        val request = HttpRequest.exchange(
          HttpMethod.Get,
          "https://api.typesafe.ai/v1/models",
          None,
          ApiKey.of("sk-life"),
          configured = Nil
        )
        run(transport.exchange(request)) match {
          case Failure(_) => () // expected — the client is shut
          case Success(_) => fail("an exchange succeeded on a released transport")
        }
    }
  }

  test("a-caller-owned-jdk-transport-is-not-closed-by-release") {
    val underlying = java.net.http.HttpClient.newHttpClient()
    val transport  = JdkHttpTransport.withClient(underlying)
    run(transport.release) match {
      case Failure(e) => fail(s"release of a caller-owned transport failed: $e")
      case Success(_) => () // no-op — the caller still owns `underlying`
    }
  }
}
