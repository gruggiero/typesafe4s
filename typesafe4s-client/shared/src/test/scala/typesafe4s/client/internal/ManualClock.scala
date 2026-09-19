package typesafe4s.client.internal

import scala.concurrent.duration.{Duration, FiniteDuration}

import kyo.compat.*

/**
  * A clock whose time only moves when a test moves it.
  *
  * Written ONCE against the carrier and compiled into every backend row, so the same deterministic timing test runs on zio, ce, ox, kyo and
  * pekko alike. That is the point: the alternative was five different ecosystem test kits (and two ecosystems that ship none), which would
  * have forced timing tests to be written per row and broken the one-suite-for-every-row rule that Ring 5 rests on.
  *
  * `sleep` parks until the test advances past its deadline; it never consumes wall-clock time. A test that never advances far enough leaves
  * the sleeper parked, which surfaces as the test timing out rather than as a silent pass.
  */
final private[typesafe4s] class ManualClock private (state: CAtomicRef[ManualClock.State]) extends Clock {

  def monotonic: CIO[FiniteDuration] = state.get.map(_.now)

  def sleep(duration: FiniteDuration): CIO[Unit] =
    if (duration <= Duration.Zero) CIO.unit
    else
      CPromise.init[Unit].flatMap { waiter =>
        state
          .updateAndGet(current => current.copy(waiters = ManualClock.Waiter(current.now + duration, waiter) :: current.waiters))
          .flatMap(_ => CIO.ensure(deregister(waiter))(waiter.get))
      }

  /**
    * Removes `waiter` from the parked set — runs on every exit of `sleep`:
    * a released waiter is already gone (advance swapped it out), and a
    * waiter whose sleep was abandoned by a race is dropped here rather than
    * left to corrupt `parked`/`nextDeadline` with a deadline nobody owns.
    */
  private def deregister(waiter: CPromise[Unit]): CIO[Unit] =
    state.updateAndGet(current => current.copy(waiters = current.waiters.filterNot(_.promise == waiter))).unit

  /**
    * Moves time forward by `by` and releases every sleeper whose deadline has passed. The swap of due sleepers out of the state is a single
    * atomic update, so a sleeper cannot be released twice or dropped when advances overlap.
    */
  def advance(by: FiniteDuration): CIO[Unit] =
    state.get.flatMap { before =>
      val target = before.now + by
      state
        .getAndUpdate(current => ManualClock.State(target, current.waiters.filter(_.deadline > target)))
        .flatMap(previous => CIO.foreachDiscard(previous.waiters.filter(_.deadline <= target))(_.promise.succeed(()).unit))
    }

  /**
    * How many sleepers are parked right now. A test asserts on this to show that a wait was entered rather than skipped.
    */
  def parked: CIO[Int] = state.get.map(_.waiters.size)

  /**
    * The earliest deadline a parked sleeper is waiting for, if any. A test driver advances to this point to
    * release exactly the next wait the code under test began — and to learn how long that wait was.
    */
  def nextDeadline: CIO[Option[FiniteDuration]] =
    state.get.map(current => current.waiters.map(_.deadline).minOption)
}

private[typesafe4s] object ManualClock {

  final private[internal] case class Waiter(deadline: FiniteDuration, promise: CPromise[Unit])

  final private[internal] case class State(now: FiniteDuration, waiters: List[Waiter])

  /**
    * A clock reading zero with nobody waiting.
    */
  def init: CIO[ManualClock] = CAtomicRef.init(State(Duration.Zero, Nil)).map(new ManualClock(_))
}
