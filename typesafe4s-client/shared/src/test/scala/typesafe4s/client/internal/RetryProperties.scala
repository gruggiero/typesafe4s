package typesafe4s.client.internal

import scala.annotation.unused
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

import kyo.compat.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.{classify, forAll}

import typesafe4s.{RequestId, RetryPolicy, TypesafeException}
import typesafe4s.TypesafeException.*

// ============================================================================
// TEST ORACLE (loop half) — spec: retry-policy (change: add-typesafe4s-sdk,
// spec 4/8)
//
// Written from the spec and the Gate-1 contract BEFORE any implementation.
// Every test cites its spec source. Generators are constructive — ScalaCheck's
// classify is informational only, so interesting cases are reached by
// construction, never by rare draws.
//
// The suite lives in shared test sources and compiles into EVERY backend row:
// the loop behaviours are asserted identically on all of them. The pure
// surface (schedule, classification, construction) is exercised in core —
// typesafe4s.RetryPolicyProperties — where `sbt core/stryker` can score it.
// ============================================================================
final class RetryProperties extends ScalaCheckSuite {

  // The Ox binding's `unsafeRun` takes an ExecutionContext; the other
  // bindings do not. A shared suite supplies one and marks it unused so the
  // rows that ignore it still compile under -Werror -Wunused.
  @unused private given ExecutionContext = munitExecutionContext

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

  // spec: retry-policy — Scenario: A failure not worth repeating. The
  // non-repeatable set, ENUMERATED so every member is covered on a short run:
  // the five durable refusals, the answer that did not fit, and the local
  // failures — none of them could pass on a later identical try.
  private val nonRepeatableFailures: List[TypesafeException] = List(
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

  // spec: retry-policy — generator strategy `genPolicyAndFailures`: the bound
  // is drawn to be *sometimes shorter than the first wait* — the case where
  // the very first wait must not be begun, reached by construction.
  private val genBoundedPolicy: Gen[RetryPolicy] =
    for {
      attempts <- Gen.choose(0, 5)
      initMs   <- Gen.frequency(1 -> Gen.const(0L), 9 -> Gen.choose(1L, 2000L))
      ceilMs   <- Gen.choose(initMs, initMs + 10000L)
      jitter   <- genJitter
      boundMs  <- Gen.frequency(
                    3 -> Gen.choose(1L, initMs.max(1L)), // bound at or below the first wait
                    7 -> Gen.choose(1L, initMs * 4 + 1500L)
                  )
    } yield RetryPolicy.of(
      attempts = attempts,
      initialWait = initMs.millis,
      ceiling = ceilMs.millis,
      jitter = jitter,
      totalBound = Some(boundMs.millis)
    )

  // Failures drawn from the repeatable set (under the default statuses and
  // flags) so the loop is always entered.
  private val genRepeatableFailure: Gen[TypesafeException] = Gen.frequency(
    2 -> Gen.const(rateLimit()),
    2 -> Gen.const(overloaded),
    3 -> Gen.choose(500, 599).map(internalServer(_)),
    2 -> Gen.const(connectionFailed),
    2 -> Gen.const(timeout)
  )

  // --------------------------------------------------------------------------
  // Properties (Ring 2)
  // --------------------------------------------------------------------------

  // spec: retry-policy — Property: The total bound is never exceeded.
  property("total-bound-is-never-exceeded") {
    forAll(genBoundedPolicy, Gen.nonEmptyListOf(genRepeatableFailure)) { (policy, failures) =>
      val run    = Await.result(
        RetryLoopDriver.run(policy)(n => failures((n - 1) % failures.size)).unsafeRun,
        30.seconds
      )
      val bound  = policy.totalBound.get
      val waited = run.waitsBegun.foldLeft(0L)(_ + _.toNanos)
      val lastN  = (run.attempts - 1) % failures.size
      classify(run.waitsBegun.isEmpty, "bound bit before the first wait", "at least one wait begun") {
        classify(run.attempts > 1, "bound allowed a retry", "bound or attempts ended the call") {
          waited < bound.toNanos &&
            run.attempts <= policy.attempts + 1 &&
            run.attempts >= 1 &&
            run.outcome == Failure(failures(lastN))
        }
      }
    }
  }

  // spec: retry-policy — Property: A failure not worth repeating is attempted
  // exactly once. The refusal set is enumerated inside the property so every
  // one is covered per generated policy.
  property("non-repeatable-failure-is-attempted-once") {
    forAll(genPolicy) { policy =>
      classify(policy.attempts == 0, "no attempts permitted", s"${policy.attempts} attempts permitted") {
        nonRepeatableFailures.forall { failure =>
          val (outcome, attempts) = Await.result(
            RetryLoopDriver.runImmediate(policy)(_ => failure).unsafeRun,
            30.seconds
          )
          attempts == 1 && outcome == Failure(failure)
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // Requirement: Only failures that could pass later are repeated
  // --------------------------------------------------------------------------

  // spec: retry-policy — Scenario: A failure worth repeating. Each retryable
  // kind gets a second attempt and the call succeeds.
  test("a-failure-worth-repeating") {
    val policy                             = RetryPolicy.of(attempts = 2, initialWait = Duration.Zero, ceiling = Duration.Zero, jitter = 0.0, totalBound = None)
    val retryable: List[TypesafeException] = List(
      rateLimit(),
      overloaded,
      internalServer(408),
      internalServer(503),
      connectionFailed,
      timeout
    )
    CIO
      .foreachDiscard(retryable) { failure =>
        for {
          counter <- CAtomicInt.init(0)
          clock   <- ManualClock.init
          result  <- Retry
                       .run(policy, clock) {
                         counter.incrementAndGet.flatMap(n => if (n == 1) CIO.fail(failure) else CIO.unit)
                       }
                       .liftToTry
          n       <- counter.get
        } yield assert(
          result == Success(()) && n == 2,
          s"${failure.getClass.getSimpleName}: expected a second attempt and success, got $result after $n attempt(s)"
        )
      }
      .unsafeRun
  }

  // spec: retry-policy — Scenario: A failure not worth repeating (concrete
  // instance; the enumerated set is covered by the property above).
  test("a-failure-not-worth-repeating") {
    val policy = RetryPolicy.of(attempts = 5, initialWait = Duration.Zero, ceiling = Duration.Zero, jitter = 0.0, totalBound = None)
    RetryLoopDriver
      .runImmediate(policy)(_ => badRequest)
      .map { case (outcome, attempts) =>
        assertEquals(attempts, 1, "a refusal the caller caused was attempted more than once")
        assertEquals(outcome, Failure(badRequest))
      }
      .unsafeRun
  }

  // spec: retry-policy — a refusal status outside the retryable set is not
  // repeated even though it lands in InternalServer (e.g. 451).
  test("a-service-fault-status-outside-the-retryable-set-is-not-repeated") {
    val policy = RetryPolicy.of(attempts = 3, initialWait = Duration.Zero, ceiling = Duration.Zero, jitter = 0.0, totalBound = None)
    RetryLoopDriver
      .runImmediate(policy)(_ => internalServer(451))
      .map { case (outcome, attempts) =>
        assertEquals(attempts, 1, "status 451 is outside {408, 429, 500–599} and must not be retried")
        assert(outcome.isFailure)
      }
      .unsafeRun
  }

  // spec: retry-policy — Scenario: Not reaching the service at all.
  test("not-reaching-the-service-at-all") {
    val repeat   = RetryPolicy.of(attempts = 1, initialWait = Duration.Zero, ceiling = Duration.Zero, jitter = 0.0, totalBound = None)
    val excluded = RetryPolicy.of(
      attempts = 1,
      initialWait = Duration.Zero,
      ceiling = Duration.Zero,
      jitter = 0.0,
      retryOnConnectionFailure = false,
      retryOnTimeout = false,
      totalBound = None
    )
    val program  =
      for {
        connDefault  <- RetryLoopDriver.runImmediate(repeat)(_ => connectionFailed)
        connExcluded <- RetryLoopDriver.runImmediate(excluded)(_ => connectionFailed)
        timeDefault  <- RetryLoopDriver.runImmediate(repeat)(_ => timeout)
        timeExcluded <- RetryLoopDriver.runImmediate(excluded)(_ => timeout)
      } yield {
        assertEquals(connDefault._2, 2, "a connection failure is repeated by default")
        assertEquals(connExcluded._2, 1, "retryOnConnectionFailure = false must exclude connection failures")
        assertEquals(timeDefault._2, 2, "a per-attempt timeout is repeated by default")
        assertEquals(timeExcluded._2, 1, "retryOnTimeout = false must exclude timeouts")
      }
    program.unsafeRun
  }

  // spec: retry-policy — Scenario: The last failure is the one reported.
  test("the-last-failure-is-the-one-reported") {
    val policy   = RetryPolicy.of(attempts = 1, initialWait = Duration.Zero, ceiling = Duration.Zero, jitter = 0.0, totalBound = None)
    val first    = internalServer(500)
    val last     = rateLimit()
    val scripted = Map(1 -> first, 2 -> last)
    RetryLoopDriver
      .runImmediate(policy)(n => scripted.getOrElse(n, last))
      .map { case (outcome, attempts) =>
        assertEquals(attempts, 2)
        assertEquals(outcome, Failure(last), "the failure reported must be the final attempt's own")
      }
      .unsafeRun
  }

  // spec: retry-policy — a Throwable outside the closed family is not a
  // failure to classify: it propagates untouched after exactly one attempt,
  // however repeatable the policy would make a service failure.
  test("a-foreign-throwable-propagates-untouched") {
    val policy  = RetryPolicy.of(attempts = 5, initialWait = Duration.Zero, ceiling = Duration.Zero, jitter = 0.0, totalBound = None)
    val foreign = new IllegalStateException("not a service failure")
    RetryLoopDriver
      .runImmediate(policy)(_ => foreign)
      .map { case (outcome, attempts) =>
        assertEquals(attempts, 1, "a foreign Throwable was attempted more than once")
        assertEquals(outcome, Failure(foreign), "a foreign Throwable was replaced rather than propagated")
      }
      .unsafeRun
  }

  // --------------------------------------------------------------------------
  // Requirement: Waiting grows, stays bounded, and is spread out
  // --------------------------------------------------------------------------

  // spec: retry-policy — Scenario: The shipped defaults — exercised in core
  // (typesafe4s.RetryPolicyProperties) where `sbt core/stryker` can score it.

  // spec: retry-policy — Scenario: Repeating turned off.
  test("repeating-turned-off") {
    val policy = RetryPolicy.of(attempts = 0, initialWait = Duration.Zero, ceiling = Duration.Zero, jitter = 0.0, totalBound = None)
    RetryLoopDriver
      .runImmediate(policy)(_ => internalServer(503))
      .map { case (outcome, attempts) =>
        assertEquals(attempts, 1, "a policy permitting no further attempts tried again")
        assert(outcome.isFailure)
      }
      .unsafeRun
  }

  // --------------------------------------------------------------------------
  // Requirement: A wait the service asks for is preferred to a derived one
  // --------------------------------------------------------------------------

  // spec: retry-policy — Scenario: The service names a delay.
  test("the-service-names-a-delay") {
    val policy = RetryPolicy.of(
      attempts = 1,
      initialWait = 1.second,
      ceiling = 5.seconds,
      jitter = 0.0,
      totalBound = None
    )
    RetryLoopDriver
      .run(policy)(_ => rateLimit(Some(7.seconds)))
      .map { run =>
        assertEquals(run.waitsBegun, List(7.seconds), "the period the service named was not waited")
        assertEquals(run.attempts, 2)
      }
      .unsafeRun
  }

  // spec: retry-policy — Scenario: The caller prefers the derived wait.
  test("the-caller-prefers-the-derived-wait") {
    val policy = RetryPolicy.of(
      attempts = 1,
      initialWait = 1.second,
      ceiling = 5.seconds,
      jitter = 0.0,
      preferServiceDelay = false,
      totalBound = None
    )
    RetryLoopDriver
      .run(policy)(_ => rateLimit(Some(7.seconds)))
      .map { run =>
        assertEquals(run.waitsBegun, List(1.second), "the derived wait was not used when the preference is off")
        assertEquals(run.attempts, 2)
      }
      .unsafeRun
  }

  // spec: retry-policy — a service-named delay is preferred but a RateLimit
  // that named none falls back to the derived wait.
  test("a-rate-limit-naming-no-delay-uses-the-derived-wait") {
    val policy = RetryPolicy.of(
      attempts = 1,
      initialWait = 1.second,
      ceiling = 5.seconds,
      jitter = 0.0,
      totalBound = None
    )
    RetryLoopDriver
      .run(policy)(_ => rateLimit(None))
      .map { run =>
        assertEquals(run.waitsBegun, List(1.second), "a RateLimit naming no delay must fall back to the derived wait")
        assertEquals(run.attempts, 2)
      }
      .unsafeRun
  }

  // --------------------------------------------------------------------------
  // Requirement: The whole call is bounded
  // --------------------------------------------------------------------------

  // spec: retry-policy — Scenario: A wait that would reach the bound.
  test("a-wait-that-would-reach-the-bound-is-not-begun") {
    val failure = internalServer(503)
    val policy  = RetryPolicy.of(
      attempts = 3,
      initialWait = 5.seconds,
      ceiling = 5.seconds,
      jitter = 0.0,
      totalBound = Some(2.seconds)
    )
    RetryLoopDriver
      .run(policy)(_ => failure)
      .map { run =>
        assertEquals(run.waitsBegun, Nil, "a wait that would reach the bound was begun")
        assertEquals(run.attempts, 1)
        assertEquals(run.outcome, Failure(failure), "the last attempt's failure was not reported")
      }
      .unsafeRun
  }

  // spec: retry-policy — Requirement: "the whole call is bounded" — the FIRST
  // attempt counts against the bound, not just the waits. The operation
  // itself consumes 25s of a 30s budget before failing, so only 5s remain
  // for a 10s wait. An implementation anchoring the budget at the first
  // failure (rather than call start) would begin the wait — this test
  // distinguishes the two (Ring 8 coverage residual).
  test("the-first-attempt-counts-against-the-bound") {
    val failure = internalServer(503)
    val policy  = RetryPolicy.of(
      attempts = 2,
      initialWait = 10.seconds,
      ceiling = 10.seconds,
      jitter = 0.0,
      totalBound = Some(30.seconds)
    )
    val program =
      for {
        clock   <- ManualClock.init
        counter <- CAtomicInt.init(0)
        result  <- Retry
                     .run(policy, clock) {
                       counter.incrementAndGet
                         .flatMap(_ => clock.advance(25.seconds))
                         .flatMap(_ => CIO.fail(failure): CIO[Unit])
                     }
                     .liftToTry
        n       <- counter.get
      } yield {
        assertEquals(n, 1, "the 10s wait must not be begun — only 5s of the 30s bound remains after the first try")
        assertEquals(result, Failure(failure))
      }
    program.unsafeRun
  }

  // spec: retry-policy — a service-named delay is preferred but is still
  // bound-gated: the 40s the service asked for would reach the 30s bound, so
  // it must not be begun (Ring 8 coverage residual — the advised path was
  // covered only indirectly before).
  test("a-service-named-wait-that-would-reach-the-bound-is-not-begun") {
    val failure = rateLimit(Some(40.seconds))
    val policy  = RetryPolicy.of(
      attempts = 1,
      initialWait = 1.second,
      ceiling = 5.seconds,
      jitter = 0.0,
      totalBound = Some(30.seconds)
    )
    RetryLoopDriver
      .run(policy)(_ => failure)
      .map { run =>
        assertEquals(run.waitsBegun, Nil, "the service-named 40s wait must not be begun inside a 30s bound")
        assertEquals(run.attempts, 1)
        assertEquals(run.outcome, Failure(failure), "the last attempt's failure was not reported")
      }
      .unsafeRun
  }

  // spec: retry-policy — Scenario: No bound set. Both retries' waits are
  // begun even though their sum far exceeds any bound.
  test("no-bound-set") {
    val policy = RetryPolicy.of(
      attempts = 2,
      initialWait = 10.seconds,
      ceiling = 20.seconds,
      jitter = 0.0,
      totalBound = None
    )
    RetryLoopDriver
      .run(policy)(_ => internalServer(503))
      .map { run =>
        assertEquals(run.attempts, 3, "a call with no bound must be limited only by the permitted attempts")
        assertEquals(run.waitsBegun, List(10.seconds, 20.seconds))
      }
      .unsafeRun
  }

  // --------------------------------------------------------------------------
  // Requirement: A policy may be chosen for one call
  // --------------------------------------------------------------------------

  // spec: retry-policy — Scenario: Overriding one call. A restrictive policy
  // governs one call while the client's own policy governs the next — the
  // policy is an argument, so nothing can leak between calls.
  test("overriding-one-call") {
    val clientPolicy = RetryPolicy.of(attempts = 2, initialWait = Duration.Zero, ceiling = Duration.Zero, jitter = 0.0, totalBound = None)
    val callPolicy   = RetryPolicy.of(attempts = 0, initialWait = Duration.Zero, ceiling = Duration.Zero, jitter = 0.0, totalBound = None)
    val program      =
      for {
        overridden <- RetryLoopDriver.runImmediate(callPolicy)(_ => internalServer(503))
        normal     <- RetryLoopDriver.runImmediate(clientPolicy)(_ => internalServer(503))
      } yield {
        assertEquals(overridden._2, 1, "the supplied policy did not govern its call")
        assertEquals(normal._2, 3, "the client's policy did not govern the next call")
      }
    program.unsafeRun
  }
}
