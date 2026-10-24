package typesafe4s.client.internal

import java.util.concurrent.atomic.AtomicLong

import scala.annotation.tailrec
import scala.concurrent.duration.*

import kyo.compat.*

import typesafe4s.RetryPolicy
import typesafe4s.client.RequestRateLimit

// ============================================================================
// #48 — the client's request permits: a generic cell rate algorithm over
// the client's `Clock`. The state is one number, the theoretical arrival
// time of the next request; a reservation moves it forward by one interval
// in a single compare-and-set, so concurrent callers each get a distinct
// slot and the rate holds however many fibers share the client.
// ============================================================================
final private[typesafe4s] class Permits(limit: RequestRateLimit) {

  private val interval: Long  = math.max(1L, 1.second.toNanos / limit.perSecond)
  private val tolerance: Long = (limit.burst - 1).toLong * interval
  private val next            = new AtomicLong(Long.MinValue)

  /**
    * Reserves a permit at monotonic time `now`, returning how long to wait before using it — or None, reserving nothing, when that wait
    * could not begin and end before `deadline` (the `mayBeginWait` rule the retry loop applies to its own waits).
    */
  @tailrec
  def reserve(now: FiniteDuration, deadline: Option[FiniteDuration]): Option[FiniteDuration] = {
    val previous = next.get
    val base     = math.max(previous, now.toNanos)
    val wait     = math.max(0L, base - tolerance - now.toNanos).nanos
    if (deadline.exists(d => !RetryPolicy.mayBeginWait(d - now, wait))) None
    else if (next.compareAndSet(previous, base + interval)) Some(wait)
    else reserve(now, deadline)
  }

  /**
    * Waits on `clock` for a permit. False when none could be had before `deadline`.
    */
  def acquire(clock: Clock, deadline: Option[FiniteDuration]): CIO[Boolean] =
    clock.monotonic.flatMap { now =>
      reserve(now, deadline) match {
        case Some(wait) => clock.sleep(wait).map(_ => true)
        case None       => CIO.value(false)
      }
    }
}
