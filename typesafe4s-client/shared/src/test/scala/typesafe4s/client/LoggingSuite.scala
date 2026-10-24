package typesafe4s.client

import java.util.concurrent.ConcurrentLinkedQueue

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{ApiKey, Entry, HttpResponse, Noul, RetryPolicy}
import typesafe4s.client.internal.{ClientCio, ClockDriver, ManualClock, StandInTransport}
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// #20 — TYPESAFE_LOG_LEVEL takes effect: attempts at Debug, retries at Info,
// failures at Warning, filtered by the configured level; no line carries
// the API key or a body; the default logger writes nothing.
// ============================================================================
final class LoggingSuite extends FunSuite {

  @unused private given ExecutionContext = munitExecutionContext

  override val munitTimeout: Duration = 60.seconds

  private val secret = "sk-logged-secret"

  private def config(level: LogLevel, logger: Logger) = TypesafeConfig(
    apiKey = ApiKey.of(secret),
    baseUrl = "https://unit.test",
    model = "test-model",
    allowance = 1.hour,
    retryPolicy = RetryPolicy.of(attempts = 1, initialWait = 500.millis, ceiling = 5.seconds, jitter = 0.0, totalBound = None),
    headers = Nil,
    logLevel = level,
    logger = logger
  )

  // the service echoes the credential and the body marker back in its refusal
  private val unavailable = HttpResponse(503, Nil, s"down, key was $secret")

  private val question = Seq("q" -> Noul(Entry.text("body-marker")))

  final private class Recording extends Logger {
    val lines                                          = new ConcurrentLinkedQueue[(LogLevel, String)]()
    def log(level: LogLevel, message: => String): Unit = { lines.add((level, message)); () }
    def all: List[(LogLevel, String)]                  = lines.asScala.toList
  }

  // two refusals: one retry, then the failure reaches the caller
  private def failingCall(level: LogLevel, logger: Logger) =
    for {
      clock     <- ManualClock.init
      transport <- StandInTransport.init(List(Step.Answer(unavailable), Step.Answer(unavailable)), Step.Answer(unavailable))
      client     = new ClientCio(config(level, logger), transport, clock)
      _         <- ClockDriver.run(clock, 1.minute)(client.systemOneDynamic("subject")(question)).liftToTry
    } yield ()

  test("at Debug, attempts, the retry and the failure are logged at their levels") {
    val recording = new Recording
    failingCall(LogLevel.Debug, recording).map { _ =>
      val lines = recording.all
      assertEquals(lines.map(_._1), List(LogLevel.Debug, LogLevel.Info, LogLevel.Debug, LogLevel.Warning))
      assertEquals(lines.head._2, "typesafe4s: POST /v1/systemone, attempt 1")
      assert(lines(1)._2.startsWith("typesafe4s: attempt 1 failed, retrying in 500 ms: "), lines(1)._2)
      assert(lines(3)._2.startsWith("typesafe4s: POST /v1/systemone failed: "), lines(3)._2)
    }.unsafeRun
  }

  test("the configured level filters out the levels below it") {
    val info    = new Recording
    val warning = new Recording
    val error   = new Recording
    val program = for {
      _ <- failingCall(LogLevel.Info, info)
      _ <- failingCall(LogLevel.Warning, warning)
      _ <- failingCall(LogLevel.Error, error)
    } yield {
      assertEquals(info.all.map(_._1), List(LogLevel.Info, LogLevel.Warning))
      assertEquals(warning.all.map(_._1), List(LogLevel.Warning))
      assertEquals(error.all, Nil)
    }
    program.unsafeRun
  }

  test("no line carries the API key or the request body") {
    val recording = new Recording
    failingCall(LogLevel.Debug, recording).map { _ =>
      assert(recording.all.nonEmpty)
      recording.all.foreach { case (_, line) =>
        assert(!line.contains(secret), line)
        assert(!line.contains("body-marker"), line)
      }
    }.unsafeRun
  }

  test("the default logger writes nothing and the call behaves as before") {
    val program = for {
      _ <- failingCall(LogLevel.Debug, Logger.noop)
    } yield assertEquals(config(LogLevel.Info, Logger.noop).logger, Logger.noop)
    program.unsafeRun
  }

  test("Logger.system writes through System.Logger without failing") {
    Logger.system.log(LogLevel.Debug, "typesafe4s: logging suite")
  }

  test("a throwing observer does not stop the log line") {
    val recording = new Recording
    val throwing  = new Observer {
      override def onFailure(method: typesafe4s.HttpMethod, path: String, failure: Throwable): Unit = throw new RuntimeException("boom")
    }
    val program   = for {
      clock     <- ManualClock.init
      transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(400, Nil, "bad")))
      client     = new ClientCio(config(LogLevel.Warning, recording).copy(observer = throwing), transport, clock)
      _         <- client.systemOneDynamic("subject")(question).liftToTry
    } yield assertEquals(recording.all.map(_._1), List(LogLevel.Warning))
    program.unsafeRun
  }
}
