package typesafe4s.client.internal

import scala.concurrent.duration.*
import scala.util.Try

import kyo.compat.*

// ============================================================================
// TEST DRIVER — runs a client call under a `ManualClock`, advancing the clock
// to each parked sleeper whose deadline lies within `horizon` of now. A
// per-attempt allowance longer than the horizon is never advanced past, so
// an attempt in flight cannot be timed out by the driver: only the waits the
// test means to elapse (retry waits, permit waits, cool-downs) are released.
// ============================================================================
private[typesafe4s] object ClockDriver {

  def run[A](clock: ManualClock, horizon: FiniteDuration)(program: CIO[A]): CIO[A] =
    for {
      settled <- CPromise.init[Try[A]]
      fiber   <- CFiber.init(program)
      _       <- fiber.onComplete(t => settled.succeed(t).unit)
      _       <- drive(clock, horizon, settled)
      outcome <- settled.get
      result  <- CIO.get(outcome)
    } yield result

  private def drive[A](clock: ManualClock, horizon: FiniteDuration, settled: CPromise[Try[A]]): CIO[Unit] = {
    def step(spinsLeft: Int): CIO[Unit] =
      settled.poll.flatMap {
        case Some(_) => CIO.unit
        case None    =>
          clock.nextDeadline.flatMap { next =>
            clock.monotonic.flatMap { now =>
              next.map(_ - now) match {
                case Some(delta) if delta <= horizon => clock.advance(delta).flatMap(_ => step(5000))
                case _ if spinsLeft <= 0             => CIO.fail(new AssertionError("the call neither waited nor settled"))
                case _                               => CIO.sleep(1.milli).flatMap(_ => step(spinsLeft - 1))
              }
            }
          }
      }
    step(5000)
  }
}
