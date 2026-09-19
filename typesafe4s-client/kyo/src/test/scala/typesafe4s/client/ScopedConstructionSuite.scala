package typesafe4s.client

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

import kyo.*
import kyo.AllowUnsafe.embrace.danger
import kyo.compat.*
import munit.FunSuite

import typesafe4s.{ApiKey, HttpResponse, RetryPolicy, TypesafeClient}
import typesafe4s.client.internal.StandInTransport
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// TEST ORACLE — spec: client-configuration — scoped construction, Kyo row
//
// The Kyo idiom: `TypesafeClient.scoped` returns the client pending
// `Scope & Sync`; `Scope.run` ends the scope — the owned transport is
// released on success and on failure. `Sync.run` hands the rest to the
// carrier.
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
        pair      <- CIO.lift(
                       Sync.Unsafe.run(
                         Scope
                           .run(TypesafeClient.scoped(config, transport).flatMap(_.listModels))
                           .flatMap(ms => transport.released.get.lower.map(r => (ms, r)))
                       )
                     )
      } yield {
        assert(pair._1.isEmpty, s"unexpected models: ${pair._1}")
        assertEquals(pair._2, 1, "the owned transport was not released when the scope ended")
      }
    program.unsafeRun
  }

  test("scoped-releases-on-failure") {
    val boom    = new RuntimeException("use failed")
    val program =
      for {
        transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, "{}")))
        outcome   <- CIO
                       .lift(
                         Sync.Unsafe.run(
                           Scope.run(
                             TypesafeClient.scoped(config, transport).flatMap(_ => Abort.fail[Throwable](boom))
                           )
                         )
                       )
                       .liftToTry
        released  <- transport.released.get
      } yield {
        outcome match {
          case Failure(e) => assertEquals(e, boom, "the use failure did not propagate")
          case Success(_) => fail("a failing use came back as a success")
        }
        assertEquals(released, 1, "the owned transport was not released when the scope failed")
      }
    program.unsafeRun
  }
}
