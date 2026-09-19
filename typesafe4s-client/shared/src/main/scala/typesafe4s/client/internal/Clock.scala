package typesafe4s.client.internal

import scala.concurrent.duration.FiniteDuration

import kyo.compat.*

/**
  * The seam through which every shared behaviour reads time and waits. The retry loop, per-attempt allowances and the batching
  * concurrency bound all go through here rather than reaching for the carrier's own clock directly.
  *
  * The reason is testability, and it is the whole justification for this indirection: no backend row ships a deterministic clock, so a
  * timing requirement expressed against the carrier's real clock can only be tested by waiting. Routing through a seam lets one virtual
  * clock, written once against the carrier, make those requirements deterministic on EVERY row at once. See `ManualClock` in the test
  * sources.
  */
private[typesafe4s] trait Clock {

  /**
    * Time since an arbitrary origin. Only differences are meaningful.
    */
  def monotonic: CIO[FiniteDuration]

  /**
    * Waits for `duration`. A non-positive duration returns immediately without yielding.
    */
  def sleep(duration: FiniteDuration): CIO[Unit]
}

private[typesafe4s] object Clock {

  /**
    * The real clock, delegating to the carrier and therefore to whichever effect system this row compiles against.
    */
  val system: Clock = new Clock {
    def monotonic: CIO[FiniteDuration]             = CIO.nowMonotonic
    def sleep(duration: FiniteDuration): CIO[Unit] = CIO.sleep(duration)
  }
}
