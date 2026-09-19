package typesafe4s.client.internal

import scala.concurrent.duration.*
import scala.util.Try

import kyo.compat.*

import typesafe4s.{HttpRequest, HttpResponse, RetryPolicy}

// ============================================================================
// TEST DRIVER — spec: http-transport (change: add-typesafe4s-sdk, spec 5/8)
//
// Drives the real `Exchange.run` pipeline under a `ManualClock`: advancing
// the clock through every wait the run parks (per-attempt allowances and
// retry waits alike) and recording the delta of each advance, so a test can
// assert the allowance each attempt was actually given. Abandonment is
// driven by racing the call against a signal that fires at an ENUMERATED
// point — never by wall-clock racing.
//
// Shared by TransportProperties (client/shared, every row) and
// TransportParitySuite (integration-tests, every published row).
// ============================================================================
private[typesafe4s] object TransportDriver {

  // spec: http-transport — generator strategy `genAbandonPoint`: the points
  // are enumerated, not timed — before the first exchange, during an
  // exchange, during a wait, or after the last attempt.
  enum AbandonPoint {
    case BeforeFirst, DuringExchange, DuringWait, AfterLast
  }

  /**
    * What a driven run observed: the call's outcome, every clock advance
    * made (each equals the wait its sleeper asked for, in order), and the
    * stand-in's counters and request log at settlement.
    */
  final case class Driven(
    outcome: Try[HttpResponse],
    deltas: List[FiniteDuration],
    begun: Int,
    completed: Int,
    aborted: Int,
    requests: Vector[HttpRequest]
  )

  /**
    * What an abandoned run observed: exchanges begun at the moment of
    * abandonment and after the world was allowed to play out — the
    * property's `exchangesBegunAfter` is their difference — plus the
    * stand-in's abort count (the exchange-in-flight signal).
    */
  final case class Abandoned(
    begunAtAbandon: Int,
    begunAfter: Int,
    aborted: Int,
    parkedLeft: Int
  )

  /**
    * Runs `Exchange.run(standIn, policy, clock, allowance)(request)` to
    * settlement, advancing `clock` through every wait it parks. A run that
    * neither parks nor settles is stuck — surfaced as a test failure after
    * a bounded number of real-time polls, never as a hang.
    */
  def runToSettlement(
    clock: ManualClock,
    standIn: StandInTransport,
    policy: RetryPolicy,
    allowance: FiniteDuration,
    request: HttpRequest
  ): CIO[Driven] =
    for {
      deltas  <- CAtomicRef.init(List.empty[FiniteDuration])
      settled <- CPromise.init[Try[HttpResponse]]
      fiber   <- CFiber.init(Exchange.run(standIn, policy, clock, allowance)(request))
      _       <- fiber.onComplete(t => settled.succeed(t).unit)
      _       <- drive(clock, settled, deltas)
      outcome <- settled.get
      ds      <- deltas.get
      b       <- standIn.begun.get
      c       <- standIn.completed.get
      a       <- standIn.aborted.get
      rs      <- standIn.requests
    } yield Driven(outcome, ds, b, c, a, rs)

  /**
    * `runToSettlement` with the clock and stand-in built inline — for tests
    * that only need the driven observation, not the parts.
    */
  def runToSettlementF(
    policy: RetryPolicy,
    allowance: FiniteDuration,
    request: HttpRequest,
    script: List[StandInTransport.Step],
    fallback: StandInTransport.Step
  ): CIO[Driven] =
    for {
      clock   <- ManualClock.init
      standIn <- StandInTransport.init(script, fallback)
      run     <- runToSettlement(clock, standIn, policy, allowance, request)
    } yield run

  /**
    * Runs the call and abandons it at `point`, then lets the world play
    * out: the clock is advanced far past every deadline and `releaseAfter`
    * opens every scripted gate, so any call that survived the abandonment
    * would plainly make another attempt — which is exactly what the pekko
    * row's declared no-op is asserted on.
    *
    * Abandonment is `CIO.race(call, signal)` — the only mechanism the
    * carrier offers (CFiber has no interrupt): when `signal` completes the
    * call branch is abandoned on every row that can abandon, and keeps
    * running on the ones that cannot.
    */
  def abandonAt(
    clock: ManualClock,
    standIn: StandInTransport,
    policy: RetryPolicy,
    allowance: FiniteDuration,
    request: HttpRequest,
    point: AbandonPoint,
    releaseAfter: CIO[Unit]
  ): CIO[Abandoned] =
    for {
      settled <- CPromise.init[Try[HttpResponse]]
      // liftToTry + succeed records real settlement only: an abandoned
      // branch never completes `settled`, which is what AfterLast waits on.
      call     = Exchange
                   .run(standIn, policy, clock, allowance)(request)
                   .liftToTry
                   .flatMap(t => settled.succeed(t).unit)
      signal   = abandonSignal(clock, standIn, settled, point)
      _       <- CIO.race(call, signal)
      atAb    <- standIn.begun.get
      _       <- clock.advance(24.hours)
      _       <- releaseAfter
      _       <- quiesce(clock, standIn)
      after   <- standIn.begun.get
      ab      <- standIn.aborted.get
      parked  <- clock.parked
    } yield Abandoned(atAb, after, ab, parked)

  private def abandonSignal(
    clock: ManualClock,
    standIn: StandInTransport,
    settled: CPromise[Try[HttpResponse]],
    point: AbandonPoint
  ): CIO[Unit] =
    point match {
      case AbandonPoint.BeforeFirst    => CIO.unit
      case AbandonPoint.DuringExchange => awaitWaiting(standIn, 1)
      case AbandonPoint.DuringWait     => RetryLoopDriver.parkedCount(clock, 1)
      case AbandonPoint.AfterLast      => awaitDone(settled)
    }

  /**
    * Advances `clock` until `settled` completes: each time a sleeper parks,
    * the delta to its deadline is recorded and the clock is moved exactly
    * there — the delta IS the wait the code under test began.
    */
  private def drive(clock: ManualClock, settled: CPromise[Try[HttpResponse]], deltas: CAtomicRef[List[FiniteDuration]]): CIO[Unit] = {
    def step(spinsLeft: Int): CIO[Unit] =
      settled.poll.flatMap {
        case Some(_) => CIO.unit
        case None    =>
          clock.parked.flatMap { parkedCount =>
            if (parkedCount > 0)
              grace(clock, settled, 200).flatMap { stillWaiting =>
                if (!stillWaiting) step(5000)
                else
                  clock.nextDeadline.flatMap {
                    case Some(deadline) =>
                      clock.monotonic.flatMap { now =>
                        val delta = deadline - now
                        deltas
                          .updateAndGet(_ :+ delta)
                          .flatMap(_ => clock.advance(delta))
                          .flatMap(_ => step(5000))
                      }
                    case None           => CIO.fail(new AssertionError("a sleeper is parked with no deadline"))
                  }
              }
            else if (spinsLeft <= 0) CIO.fail(new AssertionError("the call neither waited nor settled"))
            else CIO.sleep(1.milli).flatMap(_ => step(spinsLeft - 1))
          }
      }
    step(5000)
  }

  /**
    * Bounded spin before treating a parked waiter as a wait the code under
    * test began — it exists for waiters the code does NOT own. A race loser's
    * abandoned `clock.sleep` keeps its waiter in the parked set until the
    * loser fiber's finalizer drops it, which lags the race's return on rows
    * that interrupt lazily — so give it real time to drain rather than
    * recording the zombie's release as a wait. And on a row whose parked
    * waiter cannot be interrupted at all (kyo's `CPromise.get` is masked),
    * the zombie never drains — but by then the call itself has settled, so
    * bail out on `settled` too: an abandoned waiter left behind by a
    * finished call must never be advanced or recorded. `true` means the
    * call is still unsettled with waiters parked after the grace — the
    * earliest of which is a real wait to advance.
    */
  private def grace(clock: ManualClock, settled: CPromise[Try[HttpResponse]], spinsLeft: Int): CIO[Boolean] =
    settled.poll.flatMap {
      case Some(_) => CIO.value(false)
      case None    =>
        clock.parked.flatMap { parkedCount =>
          if (parkedCount == 0) CIO.value(false)
          else if (spinsLeft <= 0) CIO.value(true)
          else CIO.sleep(1.milli).flatMap(_ => grace(clock, settled, spinsLeft - 1))
        }
    }

  /**
    * Polls until the stand-in has at least `expected` exchanges parked on gates.
    */
  private def awaitWaiting(standIn: StandInTransport, expected: Int): CIO[Unit] = {
    def loop(attemptsLeft: Int): CIO[Unit] =
      standIn.waitingNow.get.flatMap { n =>
        if (n >= expected) CIO.unit
        else if (attemptsLeft <= 0) CIO.fail(new AssertionError(s"expected $expected parked exchange(s), saw $n"))
        else CIO.sleep(1.milli).flatMap(_ => loop(attemptsLeft - 1))
      }
    loop(5000)
  }

  /**
    * Polls until the call has settled.
    */
  private def awaitDone(settled: CPromise[Try[HttpResponse]]): CIO[Unit] = {
    def loop(attemptsLeft: Int): CIO[Unit] =
      settled.done.flatMap { isDone =>
        if (isDone) CIO.unit
        else if (attemptsLeft <= 0) CIO.fail(new AssertionError("the call never settled"))
        else CIO.sleep(1.milli).flatMap(_ => loop(attemptsLeft - 1))
      }
    loop(5000)
  }

  /**
    * Bounded spin letting abandoned branches' finalizers land — a cancelled
    * parked exchange decrements `waitingNow` and deregisters its clock
    * waiter, so quiet means the world has settled. Never fails: a surviving
    * (pekko) call legitimately stays parked on a released-later gate.
    */
  private def quiesce(clock: ManualClock, standIn: StandInTransport): CIO[Unit] = {
    def loop(attemptsLeft: Int): CIO[Unit] =
      for {
        waiting <- standIn.waitingNow.get
        parked  <- clock.parked
        _       <-
          if (waiting == 0 && parked == 0) CIO.unit
          else if (attemptsLeft <= 0) CIO.unit
          else CIO.sleep(1.milli).flatMap(_ => loop(attemptsLeft - 1))
      } yield ()
    loop(500)
  }
}
