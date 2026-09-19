package typesafe4s.integration

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{ApiKey, HttpResponse, Model, RetryPolicy}
import typesafe4s.client.{Client, LogLevel, TypesafeConfig}
import typesafe4s.client.internal.StandInTransport
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// TEST ORACLE (parity half) — spec: client-configuration (change:
// add-typesafe4s-sdk, spec 7/8)
//
// Written from the spec and the Gate-1 contract BEFORE any implementation.
//
// This suite is written ONCE, against `CIO`, and is recompiled and executed
// for EVERY backend row: the carrier-level scoped bracket, explicit
// release, and the models listing behave identically regardless of which
// artifact runs them — the per-row scoped suites then prove each
// ecosystem's own idiom lowers this same bracket.
// ============================================================================
final class ClientConfigurationParitySuite extends FunSuite {

  @unused private given ExecutionContext = munitExecutionContext

  private val config = TypesafeConfig(
    apiKey = ApiKey.of("test-key"),
    baseUrl = "https://unit.test",
    model = "test-model",
    allowance = 30.seconds,
    retryPolicy = RetryPolicy.of(attempts = 0, totalBound = None),
    headers = Nil,
    logLevel = LogLevel.Error
  )

  private val listingBody: String =
    """{"models":[{"name":"jev-1","description":"Jev One","release_date":"2026-01-02"}]}"""

  // spec: client-configuration — Ring 5 row: scoped construction releases
  // at a determined moment, on every artifact
  test("the-scoped-bracket-releases-on-success-on-every-artifact") {
    val program =
      for {
        transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, listingBody)))
        models    <- Client.scoped(config, transport)(_.listModels)
        released  <- transport.released.get
      } yield {
        assertEquals(models, List(Model("jev-1", "Jev One", "2026-01-02")))
        assertEquals(released, 1, "the owned transport was not released when the scope ended")
      }
    program.unsafeRun
  }

  // spec: client-configuration — Ring 5 row: release still happens when
  // the scope's work fails, on every artifact
  test("the-scoped-bracket-releases-on-failure-on-every-artifact") {
    val boom    = new RuntimeException("work failed")
    val program =
      for {
        transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, "{}")))
        outcome   <- Client.scoped[Unit](config, transport)(_ => CIO.fail(boom)).liftToTry
        released  <- transport.released.get
      } yield {
        outcome match {
          case Failure(e) => assertEquals(e, boom, "the work failure did not propagate")
          case Success(_) => fail("failing scoped work came back as a success")
        }
        assertEquals(released, 1, "the owned transport was not released when the scope failed")
      }
    program.unsafeRun
  }

  // spec: client-configuration — Ring 5 row: the explicit release outside a
  // scope, on every artifact
  test("close-releases-on-every-artifact") {
    val program =
      for {
        transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, "{}")))
        client     = Client.cio(config, transport)
        _         <- client.close
        released  <- transport.released.get
      } yield assertEquals(released, 1, "close did not release the owned transport")
    program.unsafeRun
  }

  // spec: client-configuration — Ring 5 row: the listing decodes the same
  // models on every artifact
  test("the-models-listing-agrees-on-every-artifact") {
    val program =
      for {
        transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, listingBody)))
        client     = Client.cio(config, transport)
        models    <- client.listModels
      } yield assertEquals(models, List(Model("jev-1", "Jev One", "2026-01-02")))
    program.unsafeRun
  }
}
