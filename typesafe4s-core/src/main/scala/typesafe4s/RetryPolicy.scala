package typesafe4s

import scala.annotation.{publicInBinary, tailrec}
import scala.compiletime.error
import scala.concurrent.duration.*

// spec: retry-policy — Concepts Introduced: RetryPolicy ("Attempts, growth,
// ceiling, jitter, which failures to repeat, total budget").
//
// The constructor is private and `@publicInBinary`: `apply` is `inline`, so
// the `new RetryPolicy` it expands at a caller's site must be reachable
// there while staying uncallable in source. `apply` (literal arguments,
// compile-time checked) and `of` (computed arguments, run-time checked) are
// the only construction paths; `copy` is private along with the ctor, and
// the class-body `require`s run on every construction.
final case class RetryPolicy @publicInBinary private (
  // further attempts after the first; 0 means a repeatable failure is still surfaced after one try
  attempts: Int,
  // the first wait; each subsequent wait doubles it before the ceiling is applied
  initialWait: FiniteDuration,
  // no derived wait exceeds this
  ceiling: FiniteDuration,
  // the largest fraction of a wait that jitter may remove, in [0, 1]
  jitter: Double,
  // the service-status failures worth repeating; consulted by RateLimit(429),
  // Overloaded(529) and InternalServer(status)
  retryableStatuses: Set[Int],
  // whether a failure that never reached the service may be repeated (default true)
  retryOnConnectionFailure: Boolean,
  // whether an attempt that ran out of its own time may be repeated (default true)
  retryOnTimeout: Boolean,
  // whether a service-named delay is preferred to the derived wait (default true)
  preferServiceDelay: Boolean,
  // the whole call's budget — first attempt and every wait counted; None = no bound
  totalBound: Option[FiniteDuration]
) {

  // Construction invariants — run on EVERY construction (apply, of, and the
  // private copy path alike). The kernel's preconditions are the same shape:
  // non-negative attempt ordinal, ceiling at least the initial wait.
  require(attempts >= 0, "a retry policy's attempt count may not be negative")
  require(jitter >= 0.0 && jitter <= 1.0, "a retry policy's jitter must be a fraction between 0 and 1")
  require(initialWait >= Duration.Zero, "a retry policy's initial wait may not be negative")
  require(ceiling >= initialWait, "a retry policy's ceiling must be at least its initial wait")
  require(
    totalBound.forall(_ > Duration.Zero),
    "a retry policy's total bound must be positive when present; absent the bound is unlimited"
  )

  // spec: retry-policy — Requirement: Only failures that could pass later are
  // repeated. EXHAUSTIVE over the closed family — no `case _`: a new member is
  // a compile error here, never a silent repeatable.
  def isRetryable(e: TypesafeException): Boolean =
    e match {
      case _: TypesafeException.BadRequest           => false
      case _: TypesafeException.Authentication       => false
      case _: TypesafeException.PermissionDenied     => false
      case _: TypesafeException.NotFound             => false
      case _: TypesafeException.UnprocessableEntity  => false
      case _: TypesafeException.RateLimit            => retryableStatuses.contains(429)
      case _: TypesafeException.Overloaded           => retryableStatuses.contains(529)
      case s: TypesafeException.InternalServer       => retryableStatuses.contains(s.status)
      case _: TypesafeException.ResponseValidation   => false
      case _: TypesafeException.ConnectionFailed     => retryOnConnectionFailure
      case _: TypesafeException.Timeout              => retryOnTimeout
      case _: TypesafeException.MissingAnswer        => false
      case _: TypesafeException.InvalidQuestion      => false
      case _: TypesafeException.MissingCredential    => false
      case _: TypesafeException.InvalidConfiguration => false
    }

  // spec: retry-policy — Requirement: A wait the service asks for is preferred
  // to a derived one. Only `RateLimit` can name a delay; the preference flag
  // turns the whole mechanism off. Exhaustive like `isRetryable`: a new
  // family member is a compile error, so a delay carried by one can never be
  // silently dropped.
  def advisedDelay(e: TypesafeException): Option[FiniteDuration] =
    e match {
      case rl: TypesafeException.RateLimit           => if (preferServiceDelay) rl.retryAfter else None
      case _: TypesafeException.BadRequest           => None
      case _: TypesafeException.Authentication       => None
      case _: TypesafeException.PermissionDenied     => None
      case _: TypesafeException.NotFound             => None
      case _: TypesafeException.UnprocessableEntity  => None
      case _: TypesafeException.Overloaded           => None
      case _: TypesafeException.InternalServer       => None
      case _: TypesafeException.ResponseValidation   => None
      case _: TypesafeException.ConnectionFailed     => None
      case _: TypesafeException.Timeout              => None
      case _: TypesafeException.MissingAnswer        => None
      case _: TypesafeException.InvalidQuestion      => None
      case _: TypesafeException.MissingCredential    => None
      case _: TypesafeException.InvalidConfiguration => None
    }
}

object RetryPolicy {

  // spec: retry-policy — Scenario: The shipped defaults ({408, 429, 500–599}).
  val defaultStatuses: Set[Int] = Set(408, 429) ++ (500 to 599).toSet

  // spec: retry-policy — Scenario: The shipped defaults + The shipped bound:
  // the `of` defaults ARE the shipped defaults, so `default` is `of()`. The
  // dynamic path is used deliberately: `apply()` would be the sole literal
  // call in main sources, so a mutant touching `apply`'s literal defaults
  // could never compile — `of()` builds the identical value and leaves the
  // literal path exercised where it belongs, at call sites.
  val default: RetryPolicy = of()

  // spec: retry-policy — Compile-Negative Obligations. `inline if` reduces
  // only on constant arguments, so a literal violation is a compile error and
  // a computed argument must go through `of`. Only `attempts` and `jitter` are
  // `inline` — the checked fields; every other parameter accepts any value.
  inline def apply(
    inline attempts: Int = 2,
    initialWait: FiniteDuration = 500.millis,
    ceiling: FiniteDuration = 5.seconds,
    inline jitter: Double = 0.25,
    retryableStatuses: Set[Int] = defaultStatuses,
    retryOnConnectionFailure: Boolean = true,
    retryOnTimeout: Boolean = true,
    preferServiceDelay: Boolean = true,
    totalBound: Option[FiniteDuration] = Some(30.seconds)
  ): RetryPolicy =
    inline if (attempts < 0)
      error("a retry policy's attempt count may not be negative")
    else inline if (jitter < 0.0 || jitter > 1.0)
      error("a retry policy's jitter must be a fraction between 0 and 1")
    else
      new RetryPolicy(
        attempts,
        initialWait,
        ceiling,
        jitter,
        retryableStatuses,
        retryOnConnectionFailure,
        retryOnTimeout,
        preferServiceDelay,
        totalBound
      )

  // The dynamic construction path — for values computed at run time (config,
  // property generators). Run-time checked by the class-body `require`s.
  def of(
    attempts: Int = 2,
    initialWait: FiniteDuration = 500.millis,
    ceiling: FiniteDuration = 5.seconds,
    jitter: Double = 0.25,
    retryableStatuses: Set[Int] = defaultStatuses,
    retryOnConnectionFailure: Boolean = true,
    retryOnTimeout: Boolean = true,
    preferServiceDelay: Boolean = true,
    totalBound: Option[FiniteDuration] = Some(30.seconds)
  ): RetryPolicy =
    new RetryPolicy(
      attempts,
      initialWait,
      ceiling,
      jitter,
      retryableStatuses,
      retryOnConnectionFailure,
      retryOnTimeout,
      preferServiceDelay,
      totalBound
    )

  // spec: retry-policy — Requirement: Waiting grows, stays bounded, and is
  // spread out. The wait before the retry that follows `attempt`, before
  // jitter: `initialWait` doubled per ordinal, clamped to `ceiling`. Mirrors
  // `RetrySchedulerKernel.baseWait` — the Ring-2 bridge property binds them.
  // Nanosecond arithmetic keeps sub-millisecond waits honest; whole-
  // millisecond inputs reduce to exactly the kernel's arithmetic.
  def baseWait(policy: RetryPolicy, attempt: Attempt): FiniteDuration =
    baseWaitNanos(
      BigInt(policy.initialWait.toNanos),
      BigInt(policy.ceiling.toNanos),
      BigInt(attempt.ordinal)
    ).toLong.nanos

  // tail-recursive with fixed-point exits — the kernel's recursion in the
  // other direction. A spec-legal policy may name Int.MaxValue attempts, so
  // the derivation must not spend one stack frame per ordinal; once the
  // ceiling (or a zero wait, which doubling cannot grow) is reached the
  // answer cannot change. `.min` IS the kernel's clamp: doubled >= ceiling
  // yields ceiling. `wait == ceiling` is a fixed point of it, so equality —
  // not >= — is the honest exit: a wait above the ceiling cannot exist.
  @tailrec
  private def baseWaitNanos(wait: BigInt, ceiling: BigInt, attempt: BigInt): BigInt =
    if (attempt == 0 || wait == 0 || wait == ceiling) wait
    else baseWaitNanos((wait * 2).min(ceiling), ceiling, attempt - 1)

  // spec: retry-policy — "a random fraction of it has been removed". `u` is
  // the drawn uniform in [0, 1]; the removed fraction is `jitter * u`, so the
  // result lies in [base * (1 - jitter), base] ⊆ [0, base]. The clamp keeps
  // that bound exact even when Double rounding would overshoot at the ends.
  // The requires are the kernel's preconditions — out-of-domain inputs are
  // refused, never silently answered (Ring 8).
  def jittered(base: FiniteDuration, jitter: Double, u: Double): FiniteDuration = {
    require(base >= Duration.Zero, "a wait to jitter may not be negative")
    require(jitter >= 0.0 && jitter <= 1.0, "the jitter fraction must lie between 0 and 1")
    require(u >= 0.0 && u <= 1.0, "the jitter draw must lie between 0 and 1")
    val kept  = 1.0 - jitter * u
    val nanos = math.floor(base.toNanos.toDouble * kept).toLong
    math.min(base.toNanos, math.max(0L, nanos)).nanos
  }

  // spec: retry-policy — Requirement: A wait the service asks for is preferred
  // to a derived one. `advised` (already flag-filtered by `advisedDelay`)
  // wins, and is not re-clamped to the policy ceiling — the service named the
  // wait; the total bound is still consulted before it is begun. A negative
  // advised delay is nonsense, not a zero wait — it is refused (Ring 8).
  def nextWait(policy: RetryPolicy, attempt: Attempt, advised: Option[FiniteDuration], u: Double): FiniteDuration = {
    require(advised.forall(_ >= Duration.Zero), "a service-named wait may not be negative")
    advised.getOrElse(jittered(baseWait(policy, attempt), policy.jitter, u))
  }

  // spec: retry-policy — Requirement: a wait that would reach the bound is not
  // begun. Mirrors `RetrySchedulerKernel.mayBeginWait`: a wait may be begun
  // only if it finishes STRICTLY inside the remaining budget. A non-positive
  // remaining budget refuses every wait — the bound is never reached. A
  // negative wait is out of domain — refused, not "permitted" (Ring 8; the
  // kernel requires wait >= 0 too).
  def mayBeginWait(remaining: FiniteDuration, wait: FiniteDuration): Boolean = {
    require(wait >= Duration.Zero, "a wait to begin may not be negative")
    wait < remaining
  }
}
