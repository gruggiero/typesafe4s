package typesafe4s.client

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

import cats.effect.IO
import kyo.compat.*
import munit.FunSuite

import typesafe4s.{ApiKey, HttpResponse, RetryPolicy, TypesafeClient}
import typesafe4s.client.internal.StandInTransport
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// TEST ORACLE — spec: client-configuration — scoped construction, Cats
// Effect row
//
// The CE idiom: `TypesafeClient.resource` is a `Resource[IO, TypesafeClient]`
// — the owned transport is released when `use` ends, on success and on
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

  test("resource-releases-on-success") {
    val program =
      for {
        transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, """{"models":[]}""")))
        models    <- CIO.lift(
                       TypesafeClient.resource(config, transport).use(_.listModels)
                     )
        released  <- transport.released.get
      } yield {
        assert(models.isEmpty, s"unexpected models: $models")
        assertEquals(released, 1, "the owned transport was not released when the resource closed")
      }
    program.unsafeRun
  }

  test("resource-releases-on-failure") {
    val boom    = new RuntimeException("use failed")
    val program =
      for {
        transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, "{}")))
        outcome   <- CIO
                       .lift(
                         TypesafeClient.resource(config, transport).use(_ => IO.raiseError[Unit](boom))
                       )
                       .liftToTry
        released  <- transport.released.get
      } yield {
        outcome match {
          case Failure(e) => assertEquals(e, boom, "the use failure did not propagate")
          case Success(_) => fail("a raising use came back as a success")
        }
        assertEquals(released, 1, "the owned transport was not released when the resource failed")
      }
    program.unsafeRun
  }
}
