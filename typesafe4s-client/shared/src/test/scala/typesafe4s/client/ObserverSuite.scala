package typesafe4s.client

import java.util.concurrent.ConcurrentLinkedQueue

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{ApiKey, Entry, HttpMethod, HttpResponse, Noul, NoulAnswer, RequestId, RetryPolicy, TypesafeException, Usage}
import typesafe4s.client.internal.{ClientCio, ClockDriver, ManualClock, StandInTransport}
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// #28 — the Observer sees each attempt, response, retry, failure and
// evaluation, in order; it never sees the API key; and a throwing observer
// changes nothing about the call. Runs under a ManualClock on every row.
// ============================================================================
final class ObserverSuite extends FunSuite {

  @unused private given ExecutionContext = munitExecutionContext

  override val munitTimeout: Duration = 60.seconds

  private val secret = "sk-observed-secret"

  private val policy = RetryPolicy.of(attempts = 1, initialWait = 500.millis, ceiling = 5.seconds, jitter = 0.0, totalBound = None)

  private def config(observer: Observer) = TypesafeConfig(
    apiKey = ApiKey.of(secret),
    baseUrl = "https://unit.test",
    model = "test-model",
    allowance = 1.hour,
    retryPolicy = policy,
    headers = Nil,
    logLevel = LogLevel.Error,
    observer = observer
  )

  private val ok = HttpResponse(
    200,
    List("x-typesafe-request-id" -> "req-9"),
    """{"model":"answer-model","answers":{"q":{"type":"noul","noul":0.9}},"usage":{"input_tokens":10,"output_tokens":5}}"""
  )

  // the service echoes the credential back in its refusal
  private val unavailable = HttpResponse(503, Nil, s"down, key was $secret")

  private val question = Seq("q" -> Noul(Entry.text("is it true?")))

  final private class Recording extends Observer {
    val events = new ConcurrentLinkedQueue[String]()

    private def record(event: String): Unit = { events.add(event); () }

    override def onAttempt(method: HttpMethod, path: String, attempt: Int): Unit                          =
      record(s"attempt $method $path $attempt")
    override def onResponse(method: HttpMethod, path: String, status: Int, elapsed: FiniteDuration): Unit =
      record(s"response $status ${elapsed.toMillis}ms")
    override def onRetry(attempt: Int, wait: FiniteDuration, failure: TypesafeException): Unit            =
      record(s"retry $attempt ${wait.toMillis}ms ${failure.getMessage}")
    override def onFailure(method: HttpMethod, path: String, failure: Throwable): Unit                    =
      record(s"failure $path ${failure.getMessage}")
    override def onEvaluation(model: String, usage: Usage, requestId: Option[RequestId]): Unit            =
      record(s"evaluation $model ${usage.inputTokens} ${requestId.map(RequestId.value)}")
    def all: List[String]                                                                                 = events.asScala.toList
  }

  private def ask(observer: Observer, script: List[Step]) =
    for {
      clock     <- ManualClock.init
      transport <- StandInTransport.init(script, Step.Answer(ok))
      client     = new ClientCio(config(observer), transport, clock)
      result    <- ClockDriver.run(clock, 1.minute)(client.systemOneDynamic("subject")(question)).liftToTry
    } yield result

  test("a call that retries once and then succeeds is observed in order") {
    val recording = new Recording
    ask(recording, List(Step.Answer(unavailable))).map { result =>
      assert(result.isSuccess, result)
      val events = recording.all
      assertEquals(events.head, "attempt Post /v1/systemone 1")
      assertEquals(events(1), "response 503 0ms")
      assert(events(2).startsWith("retry 1 500ms "), events(2))
      assertEquals(events.drop(3), List("attempt Post /v1/systemone 2", "response 200 0ms", "evaluation answer-model Some(10) Some(req-9)"))
    }.unsafeRun
  }

  test("a call that fails is reported once, with what the caller receives") {
    val recording = new Recording
    ask(recording, List(Step.Answer(unavailable), Step.Answer(unavailable))).map { result =>
      val failures = recording.all.filter(_.startsWith("failure"))
      assertEquals(failures.size, 1)
      assertEquals(failures.head, s"failure /v1/systemone ${result.failed.get.getMessage}")
    }.unsafeRun
  }

  test("no callback ever receives the API key") {
    val recording = new Recording
    ask(recording, List(Step.Answer(unavailable), Step.Answer(unavailable))).map { _ =>
      assert(recording.all.nonEmpty)
      recording.all.foreach(e => assert(!e.contains(secret), e))
    }.unsafeRun
  }

  test("a throwing observer cannot fail the call or change its result") {
    val throwing = new Observer {
      override def onAttempt(method: HttpMethod, path: String, attempt: Int): Unit                          = throw new RuntimeException("boom")
      override def onResponse(method: HttpMethod, path: String, status: Int, elapsed: FiniteDuration): Unit = throw new RuntimeException("boom")
      override def onRetry(attempt: Int, wait: FiniteDuration, failure: TypesafeException): Unit            = throw new RuntimeException("boom")
      override def onEvaluation(model: String, usage: Usage, requestId: Option[RequestId]): Unit            = throw new RuntimeException("boom")
    }
    val program  = for {
      quiet <- ask(Observer.noop, List(Step.Answer(unavailable)))
      loud  <- ask(throwing, List(Step.Answer(unavailable)))
    } yield {
      assertEquals(loud.map(_.answers.noul("q")), Try(Right(NoulAnswer(0.9))))
      assertEquals(loud.map(_.model), quiet.map(_.model))
    }
    program.unsafeRun
  }

  test("the default observer is a no-op and the configuration's rendering is unchanged") {
    assert(config(Observer.noop).toString.startsWith("TypesafeConfig("))
    assert(!config(new Recording).toString.contains("Recording"))
  }
}
