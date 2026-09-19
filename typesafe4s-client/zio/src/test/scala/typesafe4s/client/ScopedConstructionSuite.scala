package typesafe4s.client

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import kyo.compat.*
import munit.FunSuite
import zio.ZIO

import typesafe4s.{ApiKey, HttpResponse, RetryPolicy, TypesafeClient}
import typesafe4s.TypesafeException.Overloaded
import typesafe4s.client.internal.StandInTransport
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// TEST ORACLE — spec: client-configuration — scoped construction, ZIO row
//
// The ZIO idiom: `TypesafeClient.scoped` returns the client in `Scope`;
// `ZIO.scoped` ends the scope — the owned transport is released on success
// and on failure.
// ============================================================================
final class ScopedConstructionSuite extends FunSuite {

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

  test("scoped-releases-on-success") {
    val program =
      for {
        transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, """{"models":[]}""")))
        models    <- CIO.lift(
                       ZIO.scoped(
                         TypesafeClient.scoped(config, transport).flatMap(_.listModels)
                       )
                     )
        released  <- transport.released.get
      } yield {
        assert(models.isEmpty, s"unexpected models: $models")
        assertEquals(released, 1, "the owned transport was not released when the scope ended")
      }
    program.unsafeRun
  }

  test("scoped-releases-on-failure") {
    val member  = Overloaded(None, "overloaded")
    val program =
      for {
        transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, "{}")))
        outcome   <- CIO.lift(
                       ZIO
                         .scoped(
                           TypesafeClient.scoped(config, transport).flatMap(_ => ZIO.fail(member))
                         )
                         .either
                     )
        released  <- transport.released.get
      } yield {
        assertEquals(outcome, Left(member), "the use failure did not propagate")
        assertEquals(released, 1, "the owned transport was not released when the scope failed")
      }
    program.unsafeRun
  }
}
