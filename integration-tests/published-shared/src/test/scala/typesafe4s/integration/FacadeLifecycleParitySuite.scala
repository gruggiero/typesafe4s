package typesafe4s.integration

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{ApiKey, HttpResponse, Model, RetryPolicy, TypesafeClient}
import typesafe4s.client.{LogLevel, TypesafeConfig}
import typesafe4s.client.internal.StandInTransport
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// TEST ORACLE (facade half) — spec: client-configuration (change:
// add-typesafe4s-sdk, spec 7/8)
//
// Written from the spec and the Gate-1 contract BEFORE any implementation.
//
// `close` and `listModels` pass through the row's OWN facade and its own
// `lift` — the operations the spec adds to the client's surface agree on
// every published artifact. Per-row scoped construction is asserted in
// each row's own ScopedConstructionSuite (the idiom differs by ecosystem,
// so no single shared type expresses it).
// ============================================================================
final class FacadeLifecycleParitySuite extends FunSuite {

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

  // spec: client-configuration — Ring 5 row: the explicit release exists
  // on every facade and releases what the client holds
  test("close-releases-through-the-facade-on-every-artifact") {
    val program =
      for {
        transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, "{}")))
        client     = TypesafeClient.of(config, transport)
        _         <- TypesafeClient.lift(client.close)
        released  <- transport.released.get
      } yield assertEquals(released, 1, "facade close did not release the owned transport")
    program.unsafeRun
  }

  // spec: client-configuration — Ring 5 row: the models listing through
  // the row's facade and its own conversions agrees on every artifact
  test("listModels-through-the-facade-agrees-on-every-artifact") {
    val program =
      for {
        transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, listingBody)))
        client     = TypesafeClient.of(config, transport)
        models    <- TypesafeClient.lift(client.listModels)
      } yield assertEquals(models, List(Model("jev-1", "Jev One", "2026-01-02")))
    program.unsafeRun
  }
}
