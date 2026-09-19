package typesafe4s.typecontract

import scala.concurrent.duration.*

import typesafe4s.{Attempt, RetryPolicy, TypesafeException}

// ============================================================================
// TYPED CONTRACT — spec: retry-policy (change: add-typesafe4s-sdk, spec 4/8)
//
// Gate 1 artifact, approved 2026-09-18. The declarations were promoted to
// typesafe4s-core/src/main/scala/typesafe4s/{Attempt,RetryPolicy}.scala at
// Step 3; this file remains as a living compile-time assertion of the
// approved surface — if the implementation drifts from it, this file stops
// compiling.
//
// Approved surface (Gate 1):
//   opaque type Attempt = Int
//   object Attempt — val first; def of(Int): Attempt;
//     extension (a: Attempt) def ordinal: Int; def next: Attempt
//   final case class RetryPolicy @publicInBinary private (attempts: Int,
//     initialWait: FiniteDuration, ceiling: FiniteDuration, jitter: Double,
//     retryableStatuses: Set[Int], retryOnConnectionFailure: Boolean,
//     retryOnTimeout: Boolean, preferServiceDelay: Boolean,
//     totalBound: Option[FiniteDuration])
//   RetryPolicy.isRetryable(TypesafeException): Boolean — exhaustive, no `case _`
//   RetryPolicy.advisedDelay(TypesafeException): Option[FiniteDuration] — exhaustive
//   object RetryPolicy — val defaultStatuses: Set[Int]; val default: RetryPolicy;
//     inline def apply(...) literal-checked; def of(...) runtime-checked;
//     def baseWait / jittered / nextWait / mayBeginWait — pure schedule math
//
// Approved design decisions:
//   D1  Private ctor; `copy` is therefore private too — `apply`/`of` are the
//       only construction paths; class-body `require`s run on every one.
//   D2  `apply` is literal-only (`inline if` cannot reduce on dynamic args);
//       `of` is the dynamic path — `RetryPolicy.of(attempts = -1)` compiles
//       then throws (Ring-8 residual; no macro-free signature rejects a
//       literal while accepting a variable).
//   D3  Schedule math lives in `RetryPolicy`'s companion (pure, sans-IO, in
//       core) — `sbt core/stryker` is the unambiguous Ring-3 target and the
//       Ring-4 bridge a core concern.
//   D4  `retryableStatuses` consulted by RateLimit(429)/Overloaded(529)/
//       InternalServer(status); durable refusals + local members hard-false.
//   D5  `advisedDelay` extracts `RateLimit.retryAfter`, flag-gated; exhaustive
//       so a new delay-carrying member is a compile error.
//   D6  `Attempt` is an opaque type — the try ordinal the delay derives from.
//   D7  `totalBound` is Option — `None` is the spec's "No bound set" scenario.
//   D8  `of`/`apply` defaults ARE the shipped defaults.
// ============================================================================

private object RetryPolicyTypeContract {

  // surface witnesses — each would stop compiling if the promoted surface drifted

  val attemptFirstSig: Attempt           = Attempt.first
  val attemptOfSig: Int => Attempt       = Attempt.of
  val attemptOrdinalSig: Attempt => Int  = a => a.ordinal
  val attemptNextSig: Attempt => Attempt = a => a.next

  // the literal path is NOT witnessed here by a direct call: `apply`'s
  // inline checks reduce at every call site, so a mutation of `apply`'s
  // signature would break THIS file's compilation during stryker setup
  // (env poison, not a killed mutant). `outside.RetryPolicyCompileNegativeSuite`
  // witnesses it instead — its string snippets typecheck at run time.
  val policyOfSig: RetryPolicy      = RetryPolicy.of(attempts = 1)
  val policyDefaultSig: RetryPolicy = RetryPolicy.default
  val defaultStatusesSig: Set[Int]  = RetryPolicy.defaultStatuses

  val isRetryableSig: RetryPolicy => TypesafeException => Boolean                 = p => e => p.isRetryable(e)
  val advisedDelaySig: RetryPolicy => TypesafeException => Option[FiniteDuration] = p => e => p.advisedDelay(e)

  val baseWaitSig: (RetryPolicy, Attempt) => FiniteDuration                                 = RetryPolicy.baseWait
  val jitteredSig: (FiniteDuration, Double, Double) => FiniteDuration                       = RetryPolicy.jittered
  val nextWaitSig: (RetryPolicy, Attempt, Option[FiniteDuration], Double) => FiniteDuration = RetryPolicy.nextWait
  val mayBeginWaitSig: (FiniteDuration, FiniteDuration) => Boolean                          = RetryPolicy.mayBeginWait
}

// ============================================================================
// Property obligations (Ring 2) — split by what the carrier needs:
//   P1/P2 (loop-level, need CIO + ManualClock) →
//     typesafe4s-client/shared/.../internal/RetryProperties.scala
//   P3/P4 (pure surface) → typesafe4s.RetryPolicyProperties (core test
//     sources), so `sbt core/stryker` can score the schedule arithmetic
// ----------------------------------------------------------------------------
// P1 `total-bound-is-never-exceeded` — genPolicyAndFailures: constructive;
//    attempts 0–5, first wait + ceiling from a small range including zero,
//    jitter 0–1 inclusive of both ends, bound drawn sometimes shorter than
//    the first wait; failures drawn from the repeatable set. Asserts the sum
//    of waits actually begun never reaches the bound.
// P2 `non-repeatable-failure-is-attempted-once` — genPolicy + the nine
//    enumerated non-repeatable refusals. Asserts exactly one attempt.
// P3 `waits-grow-and-respect-the-ceiling` — genPolicy + attempt ordinal 0 to
//    twice the permitted attempts; jitter endpoints 0 and 1.
// P4 `retry-scheduler-kernel-bridge` — the Ring-4 bridge (mandatory): a
//    test-local mirror of `RetrySchedulerKernel` agrees with production
//    `baseWait`/`mayBeginWait` on generated whole-ms inputs.
//
// Compile-Negative Obligations — outside/RetryPolicyCompileNegativeSuite.scala
//   * `RetryPolicy(attempts = -1)` — refuses, naming the non-negative rule
//   * `RetryPolicy(jitter = -0.1)` / `RetryPolicy(jitter = 1.5)` — refuses,
//     naming the [0, 1] fraction rule
// ============================================================================
