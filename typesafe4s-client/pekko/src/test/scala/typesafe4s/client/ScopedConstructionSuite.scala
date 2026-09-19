package typesafe4s.client

import scala.annotation.unused
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{ApiKey, HttpResponse, RetryPolicy, TypesafeClient}
import typesafe4s.client.internal.StandInTransport
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// TEST ORACLE — spec: client-configuration — scoped construction,
// Pekko/Future row
//
// Future has no scope — `TypesafeClient.use` is an explicit bracket: the
// owned transport is released after `body` settles, on success and on
// failure.
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

  test("use-releases-on-success") {
    val program =
      for {
        transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, """{"models":[]}""")))
        models    <- CIO.lift(
                       TypesafeClient.use(config, transport)(_.listModels)
                     )
        released  <- transport.released.get
      } yield {
        assert(models.isEmpty, s"unexpected models: $models")
        assertEquals(released, 1, "the owned transport was not released when use ended")
      }
    program.unsafeRun
  }

  test("use-releases-on-failure") {
    val boom    = new RuntimeException("use failed")
    val program =
      for {
        transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, "{}")))
        outcome   <- CIO
                       .lift(
                         TypesafeClient.use(config, transport)(_ => Future.failed[Unit](boom))
                       )
                       .liftToTry
        released  <- transport.released.get
      } yield {
        outcome match {
          case Failure(e) => assertEquals(e, boom, "the use failure did not propagate")
          case Success(_) => fail("a failed body came back as a success")
        }
        assertEquals(released, 1, "the owned transport was not released when use failed")
      }
    program.unsafeRun
  }
}
