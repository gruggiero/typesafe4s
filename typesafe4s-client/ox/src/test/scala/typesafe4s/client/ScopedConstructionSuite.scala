package typesafe4s.client

import scala.annotation.unused
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

import kyo.compat.*
import munit.FunSuite
import ox.supervised

import typesafe4s.{ApiKey, HttpResponse, RetryPolicy, TypesafeClient}
import typesafe4s.client.internal.StandInTransport
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// TEST ORACLE — spec: client-configuration — scoped construction, Ox row
//
// The Ox idiom: `TypesafeClient.scoped` runs inside `ox.supervised` and
// registers its release as a scope finalizer — the owned transport is
// released when the supervised block ends, on success and on failure.
// ============================================================================
final class ScopedConstructionSuite extends FunSuite {

  @unused private given ExecutionContext = munitExecutionContext

  private def run[A](c: CIO[A]): Try[A] = Try(Await.result(c.unsafeRun, 30.seconds))

  private val config = TypesafeConfig(
    apiKey = ApiKey.of("test-key"),
    baseUrl = "https://unit.test",
    model = "test-model",
    allowance = 30.seconds,
    retryPolicy = RetryPolicy.of(attempts = 0, totalBound = None),
    headers = Nil,
    logLevel = LogLevel.Error
  )

  test("scoped-releases-on-success") {
    val transport = run(
      StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, """{"models":[]}""")))
    ).get
    val models    = supervised {
      TypesafeClient.scoped(config, transport).listModels
    }
    assert(models.isEmpty, s"unexpected models: $models")
    assertEquals(
      run(transport.released.get).get,
      1,
      "the owned transport was not released when the supervised scope ended"
    )
  }

  test("scoped-releases-on-failure") {
    val boom      = new RuntimeException("use failed")
    val transport = run(
      StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, "{}")))
    ).get
    Try(supervised {
      TypesafeClient.scoped(config, transport)
      throw boom
    }) match {
      case Failure(e) => assertEquals(e, boom, "the use failure did not propagate")
      case Success(_) => fail("a throwing body came back as a success")
    }
    assertEquals(
      run(transport.released.get).get,
      1,
      "the owned transport was not released when the supervised scope failed"
    )
  }
}
