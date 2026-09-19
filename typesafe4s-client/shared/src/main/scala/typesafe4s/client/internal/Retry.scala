package typesafe4s.client.internal

import scala.concurrent.duration.*
import scala.util.Random

import kyo.compat.*

import typesafe4s.{Attempt, RetryPolicy, TypesafeException}

// ============================================================================
// spec: retry-policy — Implementation Anchors: "Retry loop — shared runtime
// over the carrier — uses the carrier's sleep, monotonic clock and recovery".
//
// ONE loop, written once against the carrier — it names no backend. Time and
// waiting go through the `Clock` seam, never `CIO.sleep`/`CIO.nowMonotonic`
// directly, so every timing assertion is deterministic on every row.
// ============================================================================
private[typesafe4s] object Retry {

  /**
    * Runs `operation` under `policy`, reading time and waiting through `clock`.
    *
    * A failed attempt classified repeatable is tried again after the derived
    * wait — or the service-named one when the policy prefers it — unless the
    * wait would reach the total bound, in which case the last attempt's
    * failure is reported. A failure classified non-repeatable is surfaced at
    * once. `operation` is re-evaluated on each attempt.
    *
    * The policy is an argument, so a call cannot mutate any client's own
    * policy. The bound, when configured, is anchored at the moment the call
    * starts — the first attempt counts against it.
    */
  def run[A](policy: RetryPolicy, clock: Clock)(operation: CIO[A]): CIO[A] =
    for {
      // the call's own clock — read once, only when a bound is configured
      started <- policy.totalBound match {
                   case Some(_) => clock.monotonic.map(Some(_))
                   case None    => CIO.value(None)
                 }
      result  <- loop(policy, clock, operation, started, Attempt.first)
    } yield result

  /**
    * One attempt and, when the failure is repeatable and attempts remain, the
    * wait and the next attempt. Anything outside the closed family —
    * including interruption — propagates untouched: cancellation is not in
    * the carrier's error channel, so an abandoned wait simply never resumes.
    */
  private def loop[A](
    policy: RetryPolicy,
    clock: Clock,
    operation: CIO[A],
    started: Option[FiniteDuration],
    attempt: Attempt
  ): CIO[A] =
    operation.recover { failure =>
      failure match {
        case e: TypesafeException if policy.isRetryable(e) && attempt.ordinal < policy.attempts =>
          retryAfter(policy, clock, operation, started, attempt, e)
        case _                                                                                  =>
          CIO.fail(failure)
      }
    }

  /**
    * The wait before the retry that follows `attempt`: the service-named
    * delay when advised, else the jittered derived wait. The wait is begun
    * only if it finishes strictly inside the remaining bound — otherwise the
    * last attempt's own failure is reported, never a synthetic summary.
    */
  private def retryAfter[A](
    policy: RetryPolicy,
    clock: Clock,
    operation: CIO[A],
    started: Option[FiniteDuration],
    attempt: Attempt,
    failure: TypesafeException
  ): CIO[A] =
    for {
      u        <- CIO.defer(Random.nextDouble())
      wait      = RetryPolicy.nextWait(policy, attempt, policy.advisedDelay(failure), u)
      mayBegin <- (started, policy.totalBound) match {
                    case (Some(start), Some(bound)) =>
                      clock.monotonic.map { now =>
                        val remainingNanos = bound.toNanos - (now.toNanos - start.toNanos)
                        RetryPolicy.mayBeginWait(remainingNanos.nanos, wait)
                      }
                    case _                          =>
                      CIO.value(true)
                  }
      result   <-
        if (mayBegin) clock.sleep(wait).flatMap(_ => loop(policy, clock, operation, started, attempt.next))
        else CIO.fail(failure)
    } yield result
}
