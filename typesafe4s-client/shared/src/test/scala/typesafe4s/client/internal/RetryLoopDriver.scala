package typesafe4s.client.internal

import scala.concurrent.duration.*
import scala.util.Try

import kyo.compat.*

import typesafe4s.RetryPolicy

// ============================================================================
// TEST DRIVER — spec: retry-policy (change: add-typesafe4s-sdk, spec 4/8)
//
// Drives the real `Retry.run` loop under a `ManualClock`: a scripted
// always-failing operation counts its invocations, and the driver advances
// the clock to each parked sleeper's deadline — recording every wait the loop
// actually BEGAN. Deterministic on every row: no wall-clock assertion exists.
//
// Shared by RetryProperties (client/shared, every row) and RetryParitySuite
// (integration-tests, every published row) — the parity suite asserts the
// same driver observations on all five rows.
// ============================================================================
private[typesafe4s] object RetryLoopDriver {

  /**
    * What a driven run observed: the call's outcome, how many times the
    * operation was attempted, and the waits that were begun — each recorded
    * at the moment its sleeper was released, so it equals the wait the loop
    * asked the clock for.
    */
  final case class Outcome(outcome: Try[Unit], attempts: Int, waitsBegun: List[FiniteDuration])

  /**
    * Runs `Retry.run(policy, manualClock)(op)` to settlement, advancing the
    * clock through every wait the loop begins. `failureFor(attemptNumber)`
    * (1-based) names the failure each scripted attempt raises; the loop
    * stops re-asking only when it gives up, so a generator need not supply
    * more failures than the maximum attempts.
    */
  def run(policy: RetryPolicy)(failureFor: Int => Throwable): CIO[Outcome] =
    for {
      clock   <- ManualClock.init
      counter <- CAtomicInt.init(0)
      waits   <- CAtomicRef.init(List.empty[FiniteDuration])
      settled <- CPromise.init[Try[Unit]]
      op       = counter.incrementAndGet.flatMap(n => CIO.fail(failureFor(n)): CIO[Unit])
      fiber   <- CFiber.init(Retry.run(policy, clock)(op))
      _       <- fiber.onComplete(t => settled.succeed(t).unit)
      _       <- drive(clock, settled, waits)
      outcome <- settled.get
      n       <- counter.get
      ws      <- waits.get
    } yield Outcome(outcome, n, ws)

  /**
    * Runs the loop against the manual clock without a driver — for policies
    * whose waits are all zero, where no wait ever parks and the run settles
    * on its own. Returns the outcome and the attempt count.
    */
  def runImmediate(policy: RetryPolicy)(failureFor: Int => Throwable): CIO[(Try[Unit], Int)] =
    for {
      clock   <- ManualClock.init
      counter <- CAtomicInt.init(0)
      result  <- Retry
                   .run(policy, clock)(counter.incrementAndGet.flatMap(n => CIO.fail(failureFor(n)): CIO[Unit]))
                   .liftToTry
      n       <- counter.get
    } yield (result, n)

  /**
    * Advances `clock` until `settled` completes: each time a sleeper parks,
    * the delta to its deadline is recorded in `waits` and the clock is
    * moved exactly there. A loop that neither parks nor settles is a stuck
    * run — surfaced as a test failure after a bounded number of real-time
    * polls, never as a hang.
    */
  private def drive(clock: ManualClock, settled: CPromise[Try[Unit]], waits: CAtomicRef[List[FiniteDuration]]): CIO[Unit] = {
    def step(spinsLeft: Int): CIO[Unit] =
      settled.poll.flatMap {
        case Some(_) => CIO.unit
        case None    =>
          clock.parked.flatMap { parkedCount =>
            if (parkedCount > 0)
              clock.nextDeadline.flatMap {
                case Some(deadline) =>
                  clock.monotonic.flatMap { now =>
                    val delta = deadline - now
                    waits
                      .updateAndGet(_ :+ delta)
                      .flatMap(_ => clock.advance(delta))
                      .flatMap(_ => step(5000))
                  }
                case None           => CIO.fail(new AssertionError("a sleeper is parked with no deadline"))
              }
            else if (spinsLeft <= 0) CIO.fail(new AssertionError("the call neither waited nor settled"))
            else CIO.sleep(1.milli).flatMap(_ => step(spinsLeft - 1))
          }
      }
    step(5000)
  }

  /**
    * Waits until `clock` has at least `expected` parked sleepers — the
    * DeterministicClockSuite pattern: parking is asynchronous, so a test
    * polls the clock's own state rather than sleeping a guessed interval.
    */
  def parkedCount(clock: ManualClock, expected: Int): CIO[Unit] = {
    def loop(attemptsLeft: Int): CIO[Unit] =
      clock.parked.flatMap { parked =>
        if (parked >= expected) CIO.unit
        else if (attemptsLeft <= 0) CIO.fail(new AssertionError(s"expected $expected parked sleeper(s), saw $parked"))
        else CIO.sleep(1.milli).flatMap(_ => loop(attemptsLeft - 1))
      }
    loop(5000)
  }

  /**
    * Waits until `counter` reads at least `expected` — for observing that an
    * abandoned call kept going on a row where abandonment is a no-op.
    */
  def attemptCount(counter: CAtomicInt, expected: Int): CIO[Unit] = {
    def loop(attemptsLeft: Int): CIO[Unit] =
      counter.get.flatMap { n =>
        if (n >= expected) CIO.unit
        else if (attemptsLeft <= 0) CIO.fail(new AssertionError(s"expected $expected attempt(s), saw $n"))
        else CIO.sleep(1.milli).flatMap(_ => loop(attemptsLeft - 1))
      }
    loop(5000)
  }
}
