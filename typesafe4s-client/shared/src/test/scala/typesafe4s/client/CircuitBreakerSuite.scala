package typesafe4s.client

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.Try

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{AnswerSet, ApiKey, Entry, Evaluation, HttpResponse, Noul, RetryPolicy}
import typesafe4s.TypesafeException.{BadRequest, CircuitOpen, InternalServer}
import typesafe4s.client.internal.{Breaker, ClientCio, ManualClock, StandInTransport}
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// #50 — the circuit breaker's transitions (closed, open, half-open) under a
// scripted transport and a ManualClock: deterministic on every row.
// ============================================================================
final class CircuitBreakerSuite extends FunSuite {

  @unused private given ExecutionContext = munitExecutionContext

  override val munitTimeout: Duration = 60.seconds

  private val ok          = HttpResponse(200, Nil, """{"model":"m","answers":{"q":{"type":"noul","noul":0.5}},"usage":{}}""")
  private val unavailable = HttpResponse(503, Nil, "down")
  private val malformed   = HttpResponse(400, Nil, "bad")

  private val question = Seq("q" -> Noul(Entry.text("ok?")))

  private def config(breaker: Option[CircuitBreakerPolicy]) = TypesafeConfig(
    apiKey = ApiKey.of("breaker-key"),
    baseUrl = "https://unit.test",
    model = "m",
    allowance = 1.hour,
    retryPolicy = RetryPolicy.of(attempts = 0, totalBound = None),
    headers = Nil,
    logLevel = LogLevel.Error,
    circuitBreaker = breaker
  )

  private val policy = CircuitBreakerPolicy(failureThreshold = 2, coolDown = 10.seconds)

  final private case class Setup(clock: ManualClock, transport: StandInTransport, client: ClientCio) {
    def call: CIO[Try[Evaluation[AnswerSet]]] = client.systemOneDynamic("s")(question).liftToTry
  }

  private def setup(script: List[Step], breaker: Option[CircuitBreakerPolicy] = Some(policy)) =
    for {
      clock     <- ManualClock.init
      transport <- StandInTransport.init(script, Step.Answer(ok))
      client     = new ClientCio(config(breaker), transport, clock)
    } yield Setup(clock, transport, client)

  private def isOpen(t: Try[?], retryIn: FiniteDuration) = t.failed.toOption.contains(CircuitOpen(retryIn))

  test("closed: transient failures below the threshold go through") {
    val program = for {
      s      <- setup(List(Step.Answer(unavailable), Step.Answer(ok), Step.Answer(unavailable)))
      first  <- s.call
      second <- s.call
      third  <- s.call
      begun  <- s.transport.begun.get
    } yield {
      assert(first.failed.toOption.exists(_.isInstanceOf[InternalServer]))
      assert(second.isSuccess)
      assert(third.failed.toOption.exists(_.isInstanceOf[InternalServer]), "a success resets the count")
      assertEquals(begun, 3)
    }
    program.unsafeRun
  }

  test("open: after the threshold calls fail at once with CircuitOpen and send nothing") {
    val program = for {
      s       <- setup(List(Step.Answer(unavailable), Step.Answer(unavailable)))
      _       <- s.call
      _       <- s.call
      _       <- s.clock.advance(4.seconds)
      refused <- s.call
      begun   <- s.transport.begun.get
    } yield {
      assert(isOpen(refused, 6.seconds), refused)
      assertEquals(begun, 2)
    }
    program.unsafeRun
  }

  test("half-open: after the cool-down one trial goes through; its success closes the breaker") {
    val program = for {
      gate    <- CPromise.init[Unit]
      s       <- setup(List(Step.Answer(unavailable), Step.Answer(unavailable), Step.Parked(gate, ok)))
      _       <- s.call
      _       <- s.call
      _       <- s.clock.advance(10.seconds)
      trial   <- CFiber.init(s.call)
      _       <- waitFor(s.transport.waitingNow, 1)
      during  <- s.call
      _       <- gate.succeed(())
      settled <- CPromise.init[Try[Try[Evaluation[AnswerSet]]]]
      _       <- trial.onComplete(t => settled.succeed(t).unit)
      outcome <- settled.get
      after   <- s.call
    } yield {
      assert(isOpen(during, Duration.Zero), during)
      assert(outcome.flatten.isSuccess, outcome)
      assert(after.isSuccess, after)
    }
    program.unsafeRun
  }

  test("half-open: a failed trial opens the breaker for another cool-down") {
    val program = for {
      s       <- setup(List.fill(3)(Step.Answer(unavailable)))
      _       <- s.call
      _       <- s.call
      _       <- s.clock.advance(10.seconds)
      trial   <- s.call
      refused <- s.call
      begun   <- s.transport.begun.get
    } yield {
      assert(trial.failed.toOption.exists(_.isInstanceOf[InternalServer]), trial)
      assert(isOpen(refused, 10.seconds), refused)
      assertEquals(begun, 3)
    }
    program.unsafeRun
  }

  test("a refusal such as a 400 is not a transient failure and does not trip the breaker") {
    val program = for {
      s     <- setup(List.fill(4)(Step.Answer(malformed)))
      calls <- inSequence(4)(s.call)
    } yield assert(calls.forall(_.failed.toOption.exists(_.isInstanceOf[BadRequest])), calls)
    program.unsafeRun
  }

  test("CircuitOpen is never retried") {
    assert(!RetryPolicy.default.isRetryable(CircuitOpen(1.second)))
    assertEquals(RetryPolicy.default.advisedDelay(CircuitOpen(1.second)), None)
  }

  test("an abandoned trial leaves the next call free to be the trial") {
    val breaker = new Breaker(policy)
    breaker.record(Breaker.Admission.Normal, transient = true, Duration.Zero)
    breaker.record(Breaker.Admission.Normal, transient = true, Duration.Zero)
    val trial   = breaker.admit(10.seconds).toOption.get
    assertEquals(breaker.admit(10.seconds).left.toOption, Some(CircuitOpen(Duration.Zero)))
    breaker.abandon(trial, 11.seconds)
    assert(breaker.admit(11.seconds).isRight)
  }

  test("off by default: failures never open a breaker") {
    val program = for {
      s     <- setup(List.fill(5)(Step.Answer(unavailable)), breaker = None)
      calls <- inSequence(5)(s.call)
      begun <- s.transport.begun.get
    } yield {
      assert(calls.forall(_.failed.toOption.exists(_.isInstanceOf[InternalServer])))
      assertEquals(begun, 5)
    }
    program.unsafeRun
  }

  // one call after another, so the breaker sees them in order
  private def inSequence[A](n: Int)(call: CIO[A]): CIO[List[A]] =
    (1 to n).foldLeft(CIO.value(List.empty[A]))((acc, _) => acc.flatMap(done => call.map(done :+ _)))

  private def waitFor(counter: CAtomicInt, expected: Int): CIO[Unit] = {
    def loop(left: Int): CIO[Unit] =
      counter.get.flatMap { n =>
        if (n >= expected) CIO.unit
        else if (left <= 0) CIO.fail(new AssertionError(s"expected $expected, saw $n"))
        else CIO.sleep(1.milli).flatMap(_ => loop(left - 1))
      }
    loop(5000)
  }
}
