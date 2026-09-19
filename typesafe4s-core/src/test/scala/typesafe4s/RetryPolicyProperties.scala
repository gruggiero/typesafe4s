package typesafe4s

import scala.concurrent.duration.*

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.{classify, forAll}

import typesafe4s.TypesafeException.*

// ============================================================================
// TEST ORACLE (core half) — spec: retry-policy (change: add-typesafe4s-sdk,
// spec 4/8)
//
// The pure surface — schedule arithmetic, classification, advised delays,
// construction invariants — is exercised here in core, where `sbt
// core/stryker` can score it. Loop-level behaviour that needs the carrier
// (CIO + ManualClock) lives in typesafe4s-client/shared — see
// RetryProperties there; the classification table asserted here is also
// asserted per-row by RetryParitySuite.
//
// Generators are constructive — ScalaCheck's classify is informational only,
// so interesting cases are reached by construction, never by rare draws.
// ============================================================================
final class RetryPolicyProperties extends ScalaCheckSuite {

  // --------------------------------------------------------------------------
  // Failure fixtures — one constructor call per member of the closed family.
  // Constructors are private[typesafe4s]; this suite is inside the package.
  // --------------------------------------------------------------------------

  private val rid: Option[RequestId] = RequestId.fromHeader("oracle-rid")

  private def badRequest                                  = BadRequest(rid, "malformed")
  private def authentication                              = Authentication(rid, "unauthenticated")
  private def permissionDenied                            = PermissionDenied(rid, "forbidden")
  private def notFound                                    = NotFound(rid, "unknown")
  private def unprocessable                               = UnprocessableEntity(rid, "unprocessable")
  private def rateLimit(d: Option[FiniteDuration] = None) = RateLimit(rid, d, "too many")
  private def overloaded                                  = Overloaded(rid, "overloaded")
  private def internalServer(s: Int)                      = InternalServer(s, rid, "service fault")
  private def responseValidation                          = ResponseValidation(rid, "answers.q", "wrong shape")
  private def connectionFailed                            = ConnectionFailed(new java.io.IOException("refused"))
  private def timeout                                     = Timeout(10.seconds)
  private def missingAnswer                               = MissingAnswer("q1")
  private def invalidQuestion                             = InvalidQuestion(Some("q1"), "over the limit")
  private def missingCredential                           = MissingCredential("TYPESAFE_API_KEY")

  // --------------------------------------------------------------------------
  // Generators — all constructive, all durations whole milliseconds so the
  // same policies feed the Ring-4 bridge property.
  // --------------------------------------------------------------------------

  // spec: retry-policy — generator strategy `genPolicy`: attempts 0–5, first
  // wait and ceiling from a small range including zero, jitter 0–1 inclusive
  // of both ends.
  private val genJitter: Gen[Double] = Gen.frequency(
    1 -> Gen.const(0.0),
    1 -> Gen.const(1.0),
    8 -> Gen.choose(0.0, 1.0)
  )

  private val genPolicy: Gen[RetryPolicy] =
    for {
      attempts <- Gen.choose(0, 5)
      initMs   <- Gen.frequency(1 -> Gen.const(0L), 9 -> Gen.choose(1L, 2000L))
      ceilMs   <- Gen.choose(initMs, initMs + 10000L)
      jitter   <- genJitter
      boundMs  <- Gen.option(Gen.choose(1L, 60000L))
    } yield RetryPolicy.of(
      attempts = attempts,
      initialWait = initMs.millis,
      ceiling = ceilMs.millis,
      jitter = jitter,
      totalBound = boundMs.map(_.millis)
    )

  // spec: retry-policy — the attempt ordinal is drawn from 0 to twice the
  // permitted attempts so the ceiling is reached and passed by construction.
  private val genPolicyAndOrdinal: Gen[(RetryPolicy, Attempt)] =
    genPolicy.flatMap(p => Gen.choose(0, p.attempts * 2).map(o => (p, Attempt.of(o))))

  // The uniform draw for `jittered` — endpoints included so "no reduction"
  // and "reduced to nothing" are both exercised.
  private val genU: Gen[Double] = genJitter

  // (remaining, wait) pairs in whole millis for the mayBeginWait bridge —
  // the wait == remaining boundary is forced by construction.
  private val genRemainingWaitMs: Gen[(Long, Long)] = Gen.frequency(
    1 -> Gen.choose(0L, 60000L).map(r => (r, r)),
    9 -> Gen.zip(Gen.choose(0L, 60000L), Gen.choose(0L, 60000L))
  )

  // --------------------------------------------------------------------------
  // Properties (Ring 2)
  // --------------------------------------------------------------------------

  // spec: retry-policy — Property: Waits grow and respect the ceiling.
  property("waits-grow-and-respect-the-ceiling") {
    forAll(genPolicyAndOrdinal, genU) { case ((policy, attempt), u) =>
      val base     = RetryPolicy.baseWait(policy, attempt)
      val next     = RetryPolicy.baseWait(policy, attempt.next)
      val jittered = RetryPolicy.jittered(base, policy.jitter, u)
      classify(base >= policy.ceiling, "ceiling reached", "below ceiling") {
        classify(
          u == 0.0 || u == 1.0,
          if (u == 0.0) "jitter removes nothing" else "jitter removes the most",
          "jitter mid-range"
        ) {
          base >= policy.initialWait &&
            base <= policy.ceiling &&
            next >= base &&
            jittered >= Duration.Zero &&
            jittered <= base
        }
      }
    }
  }

  // spec: retry-policy — Bridge property (Ring 2) — mandatory. The kernel
  // lives in the leaf verified/ build and is NOT on this classpath; this
  // object re-states its arithmetic from
  // verified/src/main/scala/typesafe4s/verified/RetrySchedulerKernel.scala
  // ("Mirror, do not import" — keep in lockstep; Ring 8 diffs them).
  private object RetrySchedulerKernelMirror {
    def baseWait(initialMillis: BigInt, ceilingMillis: BigInt, attempt: BigInt): BigInt =
      if (attempt == 0) initialMillis
      else {
        val previous = baseWait(initialMillis, ceilingMillis, attempt - 1)
        val doubled  = previous * 2
        if (doubled >= ceilingMillis) ceilingMillis else doubled
      }
    def mayBeginWait(remainingMillis: BigInt, waitMillis: BigInt): Boolean              =
      waitMillis < remainingMillis
  }

  property("retry-scheduler-kernel-bridge") {
    forAll(genPolicyAndOrdinal, genRemainingWaitMs) { case ((policy, attempt), (remMs, waitMs)) =>
      val prodBase = RetryPolicy.baseWait(policy, attempt).toMillis
      val kernBase = RetrySchedulerKernelMirror.baseWait(
        BigInt(policy.initialWait.toMillis),
        BigInt(policy.ceiling.toMillis),
        BigInt(attempt.ordinal)
      )
      val prodMay  = RetryPolicy.mayBeginWait(remMs.millis, waitMs.millis)
      val kernMay  = RetrySchedulerKernelMirror.mayBeginWait(BigInt(remMs), BigInt(waitMs))
      prodBase == kernBase && prodMay == kernMay
    }
  }

  // --------------------------------------------------------------------------
  // Requirement: Only failures that could pass later are repeated
  // --------------------------------------------------------------------------

  // spec: retry-policy — the classification table, exhaustive over the closed
  // family: the five durable refusals, the answer that did not fit and the
  // local failures never repeat; statuses consult the policy's set;
  // ConnectionFailed/Timeout consult their flags.
  test("classification-consults-status-set-and-flags") {
    val default = RetryPolicy.default

    // durable and local failures are never repeatable under any policy
    val neverRepeatable = List(
      badRequest,
      authentication,
      permissionDenied,
      notFound,
      unprocessable,
      responseValidation,
      missingAnswer,
      invalidQuestion,
      missingCredential
    )
    assert(neverRepeatable.forall(e => !default.isRetryable(e)), "a durable or local failure was classified repeatable")
    val openSet         = RetryPolicy.of(retryableStatuses = Set.empty)
    assert(neverRepeatable.forall(e => !openSet.isRetryable(e)), "a durable failure became repeatable by widening the status set")

    // status-carriers consult the set
    assertEquals(default.isRetryable(rateLimit()), true, "429 ∈ default set")
    assertEquals(default.isRetryable(overloaded), true, "529 ∈ default set")
    assertEquals(default.isRetryable(internalServer(408)), true, "408 ∈ default set")
    assertEquals(default.isRetryable(internalServer(503)), true, "503 ∈ default set")
    assertEquals(default.isRetryable(internalServer(451)), false, "451 ∉ default set")
    assertEquals(openSet.isRetryable(rateLimit()), false, "removing 429 must stop rate-limit retries")
    assertEquals(openSet.isRetryable(overloaded), false, "removing 529 must stop overload retries")
    assertEquals(openSet.isRetryable(internalServer(503)), false, "an empty set retries no service fault")
    assertEquals(RetryPolicy.of(retryableStatuses = Set(451)).isRetryable(internalServer(451)), true, "a configured status is repeated")

    // not-reaching-the-service failures consult their flags
    assertEquals(default.isRetryable(connectionFailed), true)
    assertEquals(default.isRetryable(timeout), true)
    val flagsOff = RetryPolicy.of(retryOnConnectionFailure = false, retryOnTimeout = false)
    assertEquals(flagsOff.isRetryable(connectionFailed), false, "retryOnConnectionFailure = false must exclude connection failures")
    assertEquals(flagsOff.isRetryable(timeout), false, "retryOnTimeout = false must exclude timeouts")
    assertEquals(RetryPolicy.of(retryOnConnectionFailure = false).isRetryable(timeout), true, "flags are independent")
  }

  // spec: retry-policy — Requirement: a wait the service asks for. Only
  // RateLimit names a delay; the flag turns the whole mechanism off; every
  // other member advises nothing.
  test("advised-delay-is-the-service-named-one-only") {
    val default = RetryPolicy.default
    val off     = RetryPolicy.of(preferServiceDelay = false)
    val family  = List(
      badRequest,
      authentication,
      permissionDenied,
      notFound,
      unprocessable,
      rateLimit(),
      overloaded,
      internalServer(503),
      responseValidation,
      connectionFailed,
      timeout,
      missingAnswer,
      invalidQuestion,
      missingCredential
    )
    assertEquals(default.advisedDelay(rateLimit(Some(3.seconds))), Some(3.seconds))
    assertEquals(default.advisedDelay(rateLimit(None)), None, "a RateLimit naming no delay advises none")
    assertEquals(off.advisedDelay(rateLimit(Some(3.seconds))), None, "the preference flag must disable the mechanism")
    assert(family.forall(e => default.advisedDelay(e).isEmpty), "a member other than a delay-carrying RateLimit advised a delay")
  }

  // --------------------------------------------------------------------------
  // Requirement: Waiting grows, stays bounded, and is spread out
  // --------------------------------------------------------------------------

  // spec: retry-policy — Scenario: The shipped defaults.
  test("the-shipped-defaults") {
    val d = RetryPolicy.default
    assertEquals(d.attempts, 2, "two further attempts after the first")
    assertEquals(d.initialWait, 500.millis, "first wait half a second")
    assertEquals(d.ceiling, 5.seconds, "doubling to a ceiling of five seconds")
    assertEquals(d.jitter, 0.25, "a quarter of each wait removed at random")
    assertEquals(d.retryableStatuses, Set(408, 429) ++ (500 to 599), "retryable statuses")
    assertEquals(d.retryOnConnectionFailure, true)
    assertEquals(d.retryOnTimeout, true)
    assertEquals(d.preferServiceDelay, true)
    assertEquals(d.totalBound, Some(30.seconds), "thirty seconds for the whole call")
    assertEquals(RetryPolicy.defaultStatuses, Set(408, 429) ++ (500 to 599))
    assertEquals(RetryPolicy.of().attempts, 2, "the construction defaults agree with the shipped defaults")
    // NB: no DIRECT literal `RetryPolicy(...)` call may appear in this file —
    // `apply`'s inline check reduces per call site, so a mutant of `apply`'s
    // literal defaults would break this file's compilation during stryker
    // setup. The literal path is exercised by `outside`'s string snippets.
  }

  // spec: retry-policy — a derived wait doubles per attempt and is clamped
  // to the ceiling; the service-named wait is preferred and NOT re-clamped
  // (the total bound is consulted before it is begun).
  test("next-wait-prefers-the-service-delay-unclamped") {
    val policy = RetryPolicy.of(attempts = 3, initialWait = 1.second, ceiling = 5.seconds, jitter = 0.0, totalBound = None)
    assertEquals(RetryPolicy.nextWait(policy, Attempt.first, None, 0.5), 1.second)
    assertEquals(RetryPolicy.nextWait(policy, Attempt.first.next, None, 0.5), 2.seconds)
    assertEquals(RetryPolicy.nextWait(policy, Attempt.first.next.next, None, 0.5), 4.seconds)
    assertEquals(RetryPolicy.nextWait(policy, Attempt.first.next.next.next, None, 0.5), 5.seconds, "the ceiling clamps the derived wait")
    assertEquals(
      RetryPolicy.nextWait(policy, Attempt.first, Some(7.seconds), 0.5),
      7.seconds,
      "the service-named wait wins and is not re-clamped to the ceiling"
    )
  }

  // spec: retry-policy — jitter bounds: none removed at u = 0, the full
  // fraction at u = 1, never negative, never above the base.
  test("jittered-respects-the-drawn-fraction") {
    val base = 4.seconds
    assertEquals(RetryPolicy.jittered(base, 0.25, 0.0), base, "u = 0 removes nothing")
    assertEquals(RetryPolicy.jittered(base, 1.0, 1.0), Duration.Zero, "jitter 1 at u = 1 removes the whole wait")
    assertEquals(RetryPolicy.jittered(base, 0.0, 1.0), base, "jitter 0 never removes")
    assertEquals(RetryPolicy.jittered(base, 0.25, 1.0), 3.seconds, "u = 1 removes the full fraction")
  }

  // spec: retry-policy — a wait may be begun only strictly inside the
  // remaining budget: equal is refused, exceeding is refused, a spent or
  // negative budget refuses everything.
  test("a-wait-may-begin-only-strictly-inside-the-remaining-budget") {
    assertEquals(RetryPolicy.mayBeginWait(10.seconds, 9.seconds), true)
    assertEquals(RetryPolicy.mayBeginWait(10.seconds, 10.seconds), false, "reaching the bound exactly is refused")
    assertEquals(RetryPolicy.mayBeginWait(10.seconds, 11.seconds), false, "exceeding the bound is refused")
    assertEquals(RetryPolicy.mayBeginWait(Duration.Zero, 0.seconds), false, "a spent budget refuses every wait")
    assertEquals(RetryPolicy.mayBeginWait((-1).seconds, 0.seconds), false, "a negative budget refuses every wait")
  }

  // spec: retry-policy — Ring 8 regression: `of(attempts = Int.MaxValue)` is a
  // spec-legal policy, so the derivation must not spend one stack frame per
  // ordinal — the wait is derived for ANY legal ordinal, not only the small
  // ones the generators reach.
  test("a-wait-derives-for-any-legal-ordinal") {
    val policy = RetryPolicy.of(attempts = Int.MaxValue, initialWait = 1.milli, ceiling = 5.seconds, jitter = 0.0, totalBound = None)
    assertEquals(RetryPolicy.baseWait(policy, Attempt.of(Int.MaxValue)), 5.seconds, "a huge ordinal reaches the ceiling, not the stack limit")
    assertEquals(RetryPolicy.baseWait(policy, Attempt.of(100000)), 5.seconds)
  }

  // spec: retry-policy — Ring 8 regression: the public schedule functions
  // refuse the inputs the kernel's preconditions exclude rather than
  // silently answering out of domain — and the refusal names the violated
  // rule (the message text is the contract; a silent refusal is a lie).
  test("out-of-domain-schedule-inputs-are-refused") {
    val policy = RetryPolicy.of(attempts = 1, initialWait = 1.second, ceiling = 5.seconds, jitter = 0.0, totalBound = None)
    assertEquals(
      intercept[IllegalArgumentException](RetryPolicy.jittered((-1).seconds, 0.25, 0.5)).getMessage,
      "requirement failed: a wait to jitter may not be negative"
    )
    assertEquals(
      intercept[IllegalArgumentException](RetryPolicy.jittered(1.second, -0.1, 0.5)).getMessage,
      "requirement failed: the jitter fraction must lie between 0 and 1"
    )
    assertEquals(
      intercept[IllegalArgumentException](RetryPolicy.jittered(1.second, 0.25, 1.5)).getMessage,
      "requirement failed: the jitter draw must lie between 0 and 1"
    )
    assertEquals(
      intercept[IllegalArgumentException](RetryPolicy.mayBeginWait(10.seconds, (-1).seconds)).getMessage,
      "requirement failed: a wait to begin may not be negative"
    )
    assertEquals(
      intercept[IllegalArgumentException](RetryPolicy.nextWait(policy, Attempt.first, Some((-1).seconds), 0.5)).getMessage,
      "requirement failed: a service-named wait may not be negative"
    )
    assertEquals(
      intercept[IllegalArgumentException](Attempt.of(Int.MaxValue).next).getMessage,
      "requirement failed: an attempt's ordinal may not overflow"
    )
    // the boundary values are inside the domain: a zero service delay is a
    // real wait the service may name (begin at once), not a refusal
    assertEquals(RetryPolicy.nextWait(policy, Attempt.first, Some(Duration.Zero), 0.5), Duration.Zero)
    assertEquals(RetryPolicy.jittered(Duration.Zero, 0.25, 0.5), Duration.Zero)
    assertEquals(RetryPolicy.mayBeginWait(10.seconds, Duration.Zero), true)
  }

  // --------------------------------------------------------------------------
  // Construction invariants — the dynamic path is run-time checked
  // --------------------------------------------------------------------------

  // spec: retry-policy — `of` is the dynamic path; the class-body `require`s
  // are the runtime gate for every construction path (apply, of, copy).
  test("the-dynamic-path-refuses-invalid-policies") {
    assertEquals(
      intercept[IllegalArgumentException](RetryPolicy.of(attempts = -1)).getMessage,
      "requirement failed: a retry policy's attempt count may not be negative"
    )
    assertEquals(
      intercept[IllegalArgumentException](RetryPolicy.of(jitter = 1.5)).getMessage,
      "requirement failed: a retry policy's jitter must be a fraction between 0 and 1"
    )
    intercept[IllegalArgumentException](RetryPolicy.of(jitter = -0.1))
    assertEquals(
      intercept[IllegalArgumentException](RetryPolicy.of(initialWait = (-1).millis)).getMessage,
      "requirement failed: a retry policy's initial wait may not be negative"
    )
    assertEquals(
      intercept[IllegalArgumentException](RetryPolicy.of(initialWait = 1.second, ceiling = 500.millis)).getMessage,
      "requirement failed: a retry policy's ceiling must be at least its initial wait"
    )
    assertEquals(
      intercept[IllegalArgumentException](RetryPolicy.of(totalBound = Some(Duration.Zero))).getMessage,
      "requirement failed: a retry policy's total bound must be positive when present; absent the bound is unlimited"
    )
    intercept[IllegalArgumentException](RetryPolicy.of(totalBound = Some((-1).seconds)))
    // the boundary is inside the domain: ceiling equal to the initial wait
    // is a legal policy (every derived wait is the same constant)
    assertEquals(RetryPolicy.of(initialWait = 1.second, ceiling = 1.second).ceiling, 1.second)
    // the shipped `default` IS the all-defaults construction
    assertEquals(RetryPolicy.of(), RetryPolicy.default)
  }

  // spec: retry-policy — Attempt ("which try this is"): a negative ordinal is
  // refused; `first` is ordinal 0; `next` advances it.
  test("an-attempt-names-its-ordinal") {
    assertEquals(Attempt.first.ordinal, 0)
    assertEquals(Attempt.first.next.ordinal, 1)
    assertEquals(Attempt.of(3).ordinal, 3)
    assertEquals(Attempt.of(3).next.ordinal, 4)
    assertEquals(
      intercept[IllegalArgumentException](Attempt.of(-1)).getMessage,
      "requirement failed: an attempt's ordinal may not be negative"
    )
  }

  // spec: retry-policy — a non-TypesafeException is not part of the closed
  // family: it cannot be classified, and the loop must propagate it. The
  // classification surface simply does not accept one — a compile-time
  // guarantee; this test pins the runtime half: a foreign Throwable is not
  // secretly wrapped by any constructor here.
  test("the-closed-family-cannot-be-forged") {
    val foreign: Throwable = new IllegalStateException("not ours")
    assert(!foreign.isInstanceOf[TypesafeException], "a foreign throwable must never classify")
  }
}
