package typesafe4s.integration

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import kyo.compat.*
import munit.FunSuite

import typesafe4s.client.internal.ManualClock

/**
  * Ring 5 — proves the deterministic clock behaves identically on every backend row.
  *
  * This is the capability the capability profile recorded as MISSING, and the reason `retry-policy`, `http-transport` and
  * `question-batching` failed spec-lint check 18. It is written once against the carrier and executed on zio, ce, ox, kyo and pekko, so
  * closing the gap closes it everywhere rather than on one row.
  *
  * Every assertion is about ORDERING, never about elapsed wall-clock time: a test that passes because a machine was fast is exactly what
  * this suite exists to replace. Completion is observed through a promise rather than by polling a fiber, because the carrier's fiber
  * offers no poll and awaiting one would block the very thing under test.
  */
final class DeterministicClockSuite extends FunSuite {

  // DECLARED DIVERGENCE: the Ox binding's `unsafeRun` takes an ExecutionContext; the other four bindings do not. A shared suite must
  // therefore supply one, and mark it unused so the rows that ignore it still compile under -Werror -Wunused.
  @unused private given ExecutionContext = munitExecutionContext

  // A sleeper that is never released must not complete; bounding the suite keeps a stuck row a failure rather than a hang.
  override val munitTimeout: Duration = 30.seconds

  test("time does not move on its own") {
    val program =
      for {
        clock <- ManualClock.init
        first <- clock.monotonic
        _     <- CIO.sleep(5.millis) // real time passes; virtual time must not
        later <- clock.monotonic
      } yield assertEquals(first, later, "virtual time moved without being advanced")
    program.unsafeRun
  }

  test("advancing moves time by exactly the amount given") {
    val program =
      for {
        clock <- ManualClock.init
        _     <- clock.advance(5.seconds)
        _     <- clock.advance(250.millis)
        now   <- clock.monotonic
      } yield assertEquals(now, 5.seconds + 250.millis)
    program.unsafeRun
  }

  test("a sleeper waits until time passes its deadline") {
    val program =
      for {
        clock    <- ManualClock.init
        finished <- CPromise.init[Unit]
        _        <- CFiber.init(clock.sleep(10.seconds).flatMap(_ => finished.succeed(()).unit))
        _        <- parkedCount(clock, 1)
        before   <- finished.done
        _        <- clock.advance(9.seconds)
        during   <- finished.done
        _        <- clock.advance(1.second)
        _        <- finished.get
      } yield {
        assertEquals(before, false, "a sleeper completed before its deadline")
        assertEquals(during, false, "a sleeper completed while still short of its deadline")
      }
    program.unsafeRun
  }

  test("a non-positive sleep returns without parking") {
    val program =
      for {
        clock  <- ManualClock.init
        _      <- clock.sleep(Duration.Zero)
        _      <- clock.sleep((-1).second)
        parked <- clock.parked
      } yield assertEquals(parked, 0, "a non-positive sleep parked a sleeper")
    program.unsafeRun
  }

  test("one advance releases every sleeper that became due, and only those") {
    val program =
      for {
        clock <- ManualClock.init
        early <- CPromise.init[Unit]
        mid   <- CPromise.init[Unit]
        late  <- CPromise.init[Unit]
        _     <- CFiber.init(clock.sleep(1.second).flatMap(_ => early.succeed(()).unit))
        _     <- CFiber.init(clock.sleep(2.seconds).flatMap(_ => mid.succeed(()).unit))
        _     <- CFiber.init(clock.sleep(10.seconds).flatMap(_ => late.succeed(()).unit))
        _     <- parkedCount(clock, 3)
        _     <- clock.advance(2.seconds)
        _     <- early.get
        _     <- mid.get
        undue <- late.done
      } yield assertEquals(undue, false, "a sleeper past its deadline was released early")
    program.unsafeRun
  }

  test("a deadline reached exactly is released") {
    val program =
      for {
        clock    <- ManualClock.init
        finished <- CPromise.init[Unit]
        _        <- CFiber.init(clock.sleep(3.seconds).flatMap(_ => finished.succeed(()).unit))
        _        <- parkedCount(clock, 1)
        _        <- clock.advance(3.seconds)
        _        <- finished.get
        parked   <- clock.parked
      } yield assertEquals(parked, 0, "a deadline reached exactly was treated as still in the future")
    program.unsafeRun
  }

  // Parking is asynchronous: a forked sleeper must reach its sleep before time is advanced, or the advance would release nothing and the
  // test would pass for the wrong reason. This polls the clock's own state rather than waiting a guessed interval.
  private def parkedCount(clock: ManualClock, expected: Int): CIO[Unit] = {
    def loop(attemptsLeft: Int): CIO[Unit] =
      clock.parked.flatMap { parked =>
        if (parked >= expected) CIO.unit
        else if (attemptsLeft <= 0) CIO.fail(new AssertionError(s"expected $expected parked sleeper(s), saw $parked"))
        else CIO.sleep(1.milli).flatMap(_ => loop(attemptsLeft - 1))
      }
    loop(5000)
  }
}
