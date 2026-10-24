package typesafe4s.client.internal

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.concurrent.duration.*

import kyo.compat.*

import typesafe4s.{RetryPolicy, TypesafeException}
import typesafe4s.client.CircuitBreakerPolicy

// ============================================================================
// #50 — the client's circuit breaker: closed (counting transient failures
// in a row), open until a moment on the client's clock, or half-open with
// one trial call in flight. Every transition is one compare-and-set on one
// cell, so concurrent calls agree on which of them is the trial.
// ============================================================================
final private[typesafe4s] class Breaker(policy: CircuitBreakerPolicy) {

  import Breaker.*

  private val state = new AtomicReference[State](Closed(0))

  def current: State = state.get

  /**
    * Lets the call through — as a trial when the cool-down has passed — or refuses it.
    */
  @tailrec
  def admit(now: FiniteDuration): Either[TypesafeException, Admission] =
    state.get match {
      case Closed(_)                  => Right(Admission.Normal)
      case HalfOpen(_)                => Left(TypesafeException.CircuitOpen(Duration.Zero))
      case Open(until) if now < until => Left(TypesafeException.CircuitOpen(until - now))
      case open @ Open(_)             =>
        val trial = new Trial
        if (state.compareAndSet(open, HalfOpen(trial))) Right(Admission.Trialling(trial)) else admit(now)
    }

  /**
    * Records a finished call. `transient` says whether it failed with a failure the retry policy repeats.
    */
  @tailrec
  def record(admission: Admission, transient: Boolean, now: FiniteDuration): Unit = {
    val previous = state.get
    val next     = (previous, admission) match {
      case (HalfOpen(t), Admission.Trialling(mine)) if t eq mine => Some(if (transient) Open(now + policy.coolDown) else Closed(0))
      case (Closed(n), Admission.Normal) if transient            =>
        Some(if (n + 1 >= policy.failureThreshold) Open(now + policy.coolDown) else Closed(n + 1))
      case (Closed(_), Admission.Normal)                         => Some(Closed(0))
      case _                                                     => None // the breaker moved on while this call ran
    }
    next match {
      case Some(s) if !state.compareAndSet(previous, s) => record(admission, transient, now)
      case _                                            => ()
    }
  }

  /**
    * An abandoned trial ends the half-open state without a verdict: the breaker reopens with its cool-down already spent, so the next
    * call becomes the trial instead of every call being refused forever.
    */
  def abandon(admission: Admission, now: FiniteDuration): Unit =
    admission match {
      case Admission.Trialling(mine) =>
        val previous = state.get
        previous match {
          case HalfOpen(t) if t eq mine => state.compareAndSet(previous, Open(now)); ()
          case _                        => ()
        }
      case Admission.Normal          => ()
    }

  /**
    * Runs `call` under the breaker, reading time on `clock`.
    */
  def guard[A](clock: Clock, retry: RetryPolicy)(call: CIO[A]): CIO[A] =
    clock.monotonic.flatMap { now =>
      admit(now) match {
        case Left(refused)    => CIO.fail(refused)
        case Right(admission) =>
          CAtomicBoolean.init(false).flatMap { settled =>
            CIO.ensure(
              settled.get.flatMap(done => if (done) CIO.unit else clock.monotonic.map(t => abandon(admission, t)))
            )(
              call.liftToTry.flatMap { outcome =>
                val transient = outcome.failed.toOption.exists {
                  case e: TypesafeException => retry.isRetryable(e)
                  case _                    => false
                }
                clock.monotonic
                  .map(t => record(admission, transient, t))
                  .flatMap(_ => settled.set(true))
                  .flatMap(_ => CIO.get(outcome))
              }
            )
          }
      }
    }
}

private[typesafe4s] object Breaker {

  final class Trial

  sealed trait State
  final case class Closed(failures: Int)       extends State
  final case class Open(until: FiniteDuration) extends State
  final case class HalfOpen(trial: Trial)      extends State

  enum Admission {
    case Normal
    case Trialling(trial: Trial)
  }
}
