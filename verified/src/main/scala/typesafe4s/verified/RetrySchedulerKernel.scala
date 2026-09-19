package typesafe4s.verified

import stainless.annotation.*
import stainless.lang.*

/** Ring 4 — mirror kernel for the retry schedule (spec: retry-policy).
  *
  * Mirrors the arithmetic the shared retry loop performs, and nothing else. Durations are BigInt milliseconds because Stainless reasons
  * about unbounded integers natively; the production code uses a duration type, and the Ring 2 bridge property is what binds the two.
  *
  * WHY THESE FOUR. Each corresponds to a proof obligation the spec states and that no test can establish for all inputs:
  *   - `baseWait` growth and ceiling      -> "Waiting grows, stays bounded, and is spread out"
  *   - `baseWaitGrows`                    -> the same, quantified over every attempt rather than sampled
  *   - `jittered`                         -> "a random fraction of it has been removed", bounded at both ends
  *   - `remainingAfter`                   -> "The whole call is bounded, and a wait that would reach the bound is not begun"
  *
  * This kernel is a MODEL. On its own it says nothing about shipped code — the Ring 2 bridge property that runs the production function and
  * this kernel on the same generated input is what makes it evidence.
  */
object RetrySchedulerKernel:

  /** The wait before `attempt`, before jitter: the initial wait doubled per attempt and clamped to the ceiling. */
  @pure
  def baseWait(initialMillis: BigInt, ceilingMillis: BigInt, attempt: BigInt): BigInt = {
    require(initialMillis >= 0 && ceilingMillis >= initialMillis && attempt >= 0)
    decreases(attempt)
    if attempt == 0 then initialMillis
    else
      val previous = baseWait(initialMillis, ceilingMillis, attempt - 1)
      val doubled  = previous * 2
      if doubled >= ceilingMillis then ceilingMillis else doubled
  }.ensuring(r => r >= initialMillis && r <= ceilingMillis)

  /** Waits never shrink as attempts go on. Stated as its own lemma so the property is quantified, not sampled. */
  @pure
  def baseWaitGrows(initialMillis: BigInt, ceilingMillis: BigInt, attempt: BigInt): Unit = {
    require(initialMillis >= 0 && ceilingMillis >= initialMillis && attempt >= 0)
    ()
  }.ensuring(_ => baseWait(initialMillis, ceilingMillis, attempt) <= baseWait(initialMillis, ceilingMillis, attempt + 1))

  /** A wait with a fraction removed. The fraction is a rational `removed/outOf` so the model stays in integer arithmetic. */
  @pure
  def jittered(baseMillis: BigInt, removed: BigInt, outOf: BigInt): BigInt = {
    require(baseMillis >= 0 && outOf > 0 && removed >= 0 && removed <= outOf)
    baseMillis - (baseMillis * removed) / outOf
  }.ensuring(r => r >= 0 && r <= baseMillis)

  /** Whether a wait may be begun at all: only one that finishes strictly inside the remaining budget. */
  @pure
  def mayBeginWait(remainingMillis: BigInt, waitMillis: BigInt): Boolean = {
    require(remainingMillis >= 0 && waitMillis >= 0)
    waitMillis < remainingMillis
  }

  /** The budget left after a wait that was permitted. The postcondition is the spec's invariant: the budget is never reached. */
  @pure
  def remainingAfter(remainingMillis: BigInt, waitMillis: BigInt): BigInt = {
    require(remainingMillis >= 0 && waitMillis >= 0 && mayBeginWait(remainingMillis, waitMillis))
    remainingMillis - waitMillis
  }.ensuring(r => r > 0 && r <= remainingMillis)
