package typesafe4s.client

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{AnswerSet, ApiKey, Attempt, Entry, Evaluation, HttpResponse, Noul, NoulAnswer, RetryPolicy}
import typesafe4s.client.internal.{ClientCio, ClockDriver, ManualClock, StandInTransport}
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// #27 — every Evaluation reports how many attempts its call made and how
// long it took on the client's clock, waits between retries included. Runs
// under a ManualClock on every row: no wall-clock time decides an outcome.
// ============================================================================
final class EvaluationMetricsSuite extends FunSuite {

  @unused private given ExecutionContext = munitExecutionContext

  override val munitTimeout: Duration = 60.seconds

  private val policy = RetryPolicy.of(attempts = 2, initialWait = 500.millis, ceiling = 5.seconds, jitter = 0.0, totalBound = None)

  private val config = TypesafeConfig(
    apiKey = ApiKey.of("metrics-key"),
    baseUrl = "https://unit.test",
    model = "test-model",
    allowance = 1.hour,
    retryPolicy = policy,
    headers = Nil,
    logLevel = LogLevel.Error
  )

  private val ok = HttpResponse(
    200,
    Nil,
    """{"model":"answer-model","answers":{"q":{"type":"noul","noul":0.9}},"usage":{"input_tokens":10,"output_tokens":5}}"""
  )

  private val unavailable = HttpResponse(503, Nil, "unavailable")

  private val question = Noul(Entry.text("is it true?"))

  private def ask(script: List[Step]): CIO[Evaluation[AnswerSet]] =
    for {
      clock      <- ManualClock.init
      transport  <- StandInTransport.init(script, Step.Answer(ok))
      client      = new ClientCio(config, transport, clock)
      evaluation <- ClockDriver.run(clock, 1.minute)(client.systemOneDynamic("subject")(Seq("q" -> question)))
    } yield evaluation

  test("a call that succeeds first time reports one attempt and no elapsed time") {
    ask(Nil).map { evaluation =>
      assertEquals(evaluation.attempts, 1)
      assertEquals(evaluation.elapsed, Duration.Zero)
    }.unsafeRun
  }

  test("a call that succeeds after two retries reports three attempts and the waits between them") {
    val waits = RetryPolicy.nextWait(policy, Attempt.of(0), None, 0.5) + RetryPolicy.nextWait(policy, Attempt.of(1), None, 0.5)
    ask(List(Step.Answer(unavailable), Step.Answer(unavailable))).map { evaluation =>
      assertEquals(evaluation.answers.noul("q"), Right(NoulAnswer(0.9)))
      assertEquals(evaluation.attempts, 3)
      assertEquals(evaluation.elapsed, waits)
    }.unsafeRun
  }

  test("the typed surface carries both fields across the positional assembly") {
    val program = for {
      clock     <- ManualClock.init
      transport <- StandInTransport.init(List(Step.Answer(unavailable)), Step.Answer(ok))
      client     = new ClientCio(config, transport, clock)
      typed     <- ClockDriver.run(clock, 1.minute)(client.systemOne("subject")((q = question)))
    } yield {
      assertEquals(typed.attempts, 2)
      assertEquals(typed.elapsed, RetryPolicy.nextWait(policy, Attempt.of(0), None, 0.5))
    }
    program.unsafeRun
  }

  test("map keeps the attempts and the elapsed time") {
    val evaluation = Evaluation("a", "m", typesafe4s.Usage.empty, None, 3, 2.seconds)
    val mapped     = evaluation.map(_.length)
    assertEquals((mapped.attempts, mapped.elapsed), (3, 2.seconds))
  }

  test("combine sums the attempts and keeps the longest elapsed time") {
    def one(name: String, attempts: Int, elapsed: FiniteDuration): Evaluation[AnswerSet] = {
      val set = AnswerSet(Map(name -> NoulAnswer(0.5)), "m", typesafe4s.Usage.empty, None)
      Evaluation(set, "m", set.usage, None, attempts, elapsed)
    }
    val combined                                                                         = Evaluation.combine(List(one("a", 1, 1.second), one("b", 3, 4.seconds)))
    assertEquals(combined.map(e => (e.attempts, e.elapsed)), Right((4, 4.seconds)))
  }
}
