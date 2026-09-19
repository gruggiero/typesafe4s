# Spec: Retry Policy

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Retry | Retry/classify, Retry/schedule, Retry/override — this spec is that concept's whole behaviour | [retry.md](../../../../concepts/retry.md) |
| Evaluation | Evaluation/ask — the call whose failures are retried and whose budget is bounded | [evaluation.md](../../../../concepts/evaluation.md) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| RetryPolicy | product type, data only | Attempts, growth, ceiling, jitter, which failures to repeat, total budget |
| Attempt | ordinal | Which try this is, from which the delay is derived |

## ADDED Requirements

### Requirement: Only failures that could pass later are repeated

Retry/classify SHALL repeat only failures whose outcome could differ on a later try, and SHALL surface
every other failure at once.

**Given** an attempt has failed
**When** the failure is classified
**Then** it is repeated only if a later try could end differently
**And** any other failure is surfaced without a second try

**Rationale**: Repeating a refusal about the request itself burns the caller's budget and the service's
quota to be told the same thing again.

#### Scenario: A failure worth repeating

**Given** the service reports too many requests, a timeout on its side, or a service fault
**When** the failure is classified
**Then** the call is tried again under the configured policy

#### Scenario: A failure not worth repeating

**Given** the service refuses the request as malformed, unauthenticated, forbidden, unknown, or unprocessable
**When** the failure is classified
**Then** the call fails at once
**And** no second attempt is made

#### Scenario: Not reaching the service at all

**Given** an attempt fails without reaching the service, or runs out of its own time
**When** the failure is classified
**Then** it is repeated by default
**And** either kind may be excluded by configuration

#### Scenario: The last failure is the one reported

**Given** every permitted attempt has failed
**When** the call gives up
**Then** the failure reported is the one from the final attempt
**And** it is not replaced by a summary of having given up

### Requirement: Waiting grows, stays bounded, and is spread out

Retry/schedule SHALL derive each wait from the attempt ordinal such that waits grow, never exceed a
ceiling, and have a random fraction removed.

**Given** a call is about to be tried again
**When** the wait is derived
**Then** it is longer than the previous wait before jitter
**And** it does not exceed the ceiling
**And** a random fraction of it has been removed

**Rationale**: Growth stops a struggling service being hammered; the ceiling stops waits becoming
useless; the random reduction stops many clients returning in step and recreating the surge.

#### Scenario: The shipped defaults

**Given** a caller who configured no policy
**When** the policy is read
**Then** it permits two further attempts after the first
**And** its first wait is half a second, doubling to a ceiling of five seconds
**And** a quarter of each wait is removed at random

#### Scenario: Waits grow and stay under the ceiling

**Given** a sequence of attempts
**When** their waits are derived
**Then** each is at least the previous one before jitter
**And** none exceeds the ceiling

#### Scenario: Repeating turned off

**Given** a policy permitting no further attempts
**When** a repeatable failure occurs
**Then** exactly one attempt was made
**And** the failure is surfaced

### Requirement: A wait the service asks for is preferred to a derived one

Retry/schedule SHALL wait for the period the service itself named, in preference to the derived wait,
unless the caller has turned that preference off.

**Given** the service named how long to wait
**When** the next wait is derived
**Then** the service's period is used
**And** the derived wait is not

**Rationale**: The service knows when its own capacity returns. Ignoring that and returning earlier makes
the overload worse and wastes the attempt.

#### Scenario: The service names a delay

**Given** a too-many-requests refusal naming how long to wait
**When** the next wait is derived
**Then** the period the service named is waited

#### Scenario: The caller prefers the derived wait

**Given** a policy with the preference turned off
**When** the service names a delay
**Then** the derived wait is used instead

### Requirement: The whole call is bounded, and a wait that would reach the bound is not begun

Retry/schedule SHALL bound the total duration of Evaluation/ask, counting the first attempt and every
wait, and SHALL not begin a wait that would reach that bound.

**Given** a call with a total bound
**When** the next wait would reach or pass it
**Then** that wait is not begun
**And** the failure from the last attempt is reported

**Rationale**: A caller who set a bound wants an answer or a failure within it. Beginning a wait that
cannot complete inside the bound guarantees overrunning it.

#### Scenario: The shipped bound

**Given** a caller who configured no policy
**When** the bound is read
**Then** it is thirty seconds for the whole call

#### Scenario: A wait that would reach the bound

**Given** the remaining bound is shorter than the next wait
**When** the next wait is considered
**Then** it is not begun
**And** the last attempt's failure is reported rather than a bound-specific one

#### Scenario: No bound set

**Given** a policy with no total bound
**When** the call runs
**Then** it is limited only by the permitted attempts and each attempt's own time

#### Scenario: The caller goes away mid-wait

**Given** a call is waiting before its next attempt
**When** the caller abandons the call
**Then** no further attempt is made
**And** the remaining bound is not consumed

### Requirement: A policy may be chosen for one call

Retry/override SHALL accept a policy for a single call, leaving the client's own policy in force for
every other call.

**Given** a caller supplies a policy for one call
**When** that call runs
**Then** the supplied policy governs it
**And** later calls use the client's policy

**Rationale**: One expensive or one throwaway call rarely justifies a second client.

#### Scenario: Overriding one call

**Given** a client with its own policy
**When** a caller supplies a different policy for a single call
**Then** that call follows the supplied policy
**And** the next call follows the client's policy

## Properties (Ring 2)

### Property: The total bound is never exceeded

**Invariant**: For any policy and any sequence of failures, the sum of the waits actually begun, plus the
attempts made, never reaches the total bound.

**Generator strategy**: `genPolicyAndFailures` — constructive. Draws a policy with attempts 0–5, first
wait and ceiling from a small range including zero, jitter 0–1 inclusive of both ends, and a bound drawn
to be *sometimes shorter than the first wait* — the case where the very first wait must not be begun, and
the one most likely to be missed. Failures are drawn from the repeatable set so the loop is always
entered. No filtering. `classify` labels: whether the bound bit, and at which attempt.

```
forAll { (policy: RetryPolicy, failures: List[Failure]) =>
  val begun = simulate(policy, failures).waitsBegun
  begun.sum < policy.totalBound
}
```

### Property: A failure not worth repeating is attempted exactly once

**Invariant**: For any policy permitting any number of attempts, a failure classified as not worth
repeating results in exactly one attempt.

**Generator strategy**: `genPolicy` reused, paired with a failure drawn from the enumerated
non-repeatable refusals — enumerated rather than sampled so every one is covered on a short run.
`classify` labels: the refusal drawn, the attempt count configured.

```
forAll { (policy: RetryPolicy, failure: NonRepeatableFailure) =>
  simulate(policy, List(failure)).attempts == 1
}
```

### Property: Waits grow and respect the ceiling

**Invariant**: Before jitter, each derived wait is at least the previous one and never exceeds the
ceiling; after jitter, each wait lies between zero and the pre-jitter value.

**Generator strategy**: `genPolicy` reused, with the attempt ordinal drawn from 0 to twice the permitted
attempts so the ceiling is reached and passed by construction. Jitter includes 0 and 1 at both ends, so
"no reduction" and "reduced to nothing" are both exercised. `classify` labels: whether the ceiling was
reached, jitter bucket.

```
forAll { (policy: RetryPolicy, attempt: Attempt) =>
  val base = baseWait(policy, attempt)
  val actual = jittered(policy, base)
  base <= policy.ceiling && actual >= 0 && actual <= base
}
```

## Requirement ↔ Test Cross-Reference

| Requirement | Test Name (Ring 2) | Test Name (Ring 3) | Ring 5 (parity) |
|-------------|--------------------|--------------------|-----------------|
| Requirement: Only failures that could pass later are repeated | `non-repeatable-failure-is-attempted-once` | `—` | asserted per row |
| Requirement: Waiting grows, stays bounded, and is spread out | `waits-grow-and-respect-the-ceiling` | `—` | asserted per row |
| Requirement: A wait the service asks for is preferred to a derived one | `service-named-delay-is-preferred` | `—` | asserted per row |
| Requirement: The whole call is bounded, and a wait that would reach the bound is not begun | `total-bound-is-never-exceeded` | `—` | asserted per row |
| Scenario: The caller goes away mid-wait | `abandoning-stops-further-attempts` | `—` | **declared divergence — see below** |
| Requirement: A policy may be chosen for one call | `per-call-policy-governs-only-that-call` | `—` | asserted per row |

## Cross-Backend Parity (Ring 5)

**Applies: YES.** The retry loop lives once in the shared client and compiles into every row. It needs a
clock, a wait that can be abandoned, and cleanup — the three things ecosystems implement differently.

| Behaviour | zio | ce | ox | kyo | pekko | Declared where |
|-----------|-----|----|----|-----|-------|----------------|
| Waits and bound behave identically | yes | yes | yes | yes | yes | this spec |
| Abandoning mid-wait stops further attempts | yes | yes | yes | yes | **no** | this spec |
| The remaining bound is not consumed after abandonment | yes | yes | yes | yes | **no** | this spec |

The Pekko row rides on a foundation with no notion of abandoning work, so abandonment is a documented
no-op there. Hiding that behind a uniform surface would leave a caller believing they had stopped a
retry loop that is in fact still running.

**Obligation:** the parity suite runs the timing and classification assertions on all five rows, and the
abandonment assertions on the four rows that support it, asserting the documented no-op on Pekko.

> ✅ **Capability resolved (2026-09-17).** This spec previously could not be implemented: there was no
> deterministic time control on any row, so every timing assertion here would have been a wall-clock test,
> which check 18 fails outright. A VirtualTime clock is now in place — written once against the carrier and
> green on all five rows — and the shared runtime reads time through a `Clock` seam so tests can supply it.
> **No assertion in this spec needs wall-clock time.** `openspec/capability-profile.md` records the detail.

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A policy with a negative attempt count | An attempt count below zero has no meaning | `compileErrors` when written in source |
| A policy with a jitter fraction outside zero to one | A fraction removed must be a fraction | `compileErrors` when written in source |

## Formal Contracts (Ring 4)

Ring 4 applies to this spec and to no other part of the retry loop: the schedule is **pure arithmetic over
values**, which is exactly what a solver can quantify over and a finite set of tests cannot. Everything else here
— the clock, the cancellation, the classification of a live failure — is effectful and belongs to Rings 2 and 5.

The kernel is `RetrySchedulerKernel` in the leaf `verified/` build. It is a **model**: on its own it says nothing
about shipped code. The Ring 2 **bridge property** below is what makes it evidence, and it is not optional.

**Verified 2026-09-17: 13/13 conditions valid on `nativez3`** (`scripts/ring4.sh`).

### Contract: baseWait

**Precondition** (`require`): the initial wait is non-negative and the ceiling is at least the initial wait; the
attempt ordinal is non-negative.
**Postcondition** (`ensuring`): the result is at least the initial wait and never exceeds the ceiling.

### Contract: baseWaitGrows

**Precondition**: as above.
**Postcondition**: the wait for one attempt never exceeds the wait for the next — growth quantified over **every**
attempt, not sampled at a few.

### Contract: jittered

**Precondition**: the base wait is non-negative and the removed fraction lies between none and all of it.
**Postcondition**: the jittered wait is between zero and the base wait inclusive — so jitter can remove nothing
or everything, and can never produce a negative wait or lengthen one.

### Contract: remainingAfter

**Precondition**: the wait was permitted, i.e. it finishes strictly inside the remaining budget.
**Postcondition**: the budget left afterwards is **strictly positive** and no larger than before. This is the
spec's central invariant — the budget is never reached — stated once, for all inputs.

### Bridge property (Ring 2) — mandatory

**Invariant**: for any policy and attempt ordinal, the production scheduler and the kernel compute the same wait,
and agree on whether a wait may be begun.

**Generator strategy**: `genPolicyAndFailures` reused, with durations converted to whole milliseconds so the two
sides are comparable. Constructive — the same generator that drives the other retry properties, so the bridge is
exercised on the same inputs rather than on a separate, friendlier distribution.

```
forAll { (policy: RetryPolicy, attempt: Attempt) =>
  productionBaseWait(policy, attempt) == RetrySchedulerKernel.baseWait(policy.initialMillis, policy.ceilingMillis, attempt) &&
    productionMayWait(policy, attempt) == RetrySchedulerKernel.mayBeginWait(policy.remainingMillis, policy.waitMillis)
}
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Only failures that could pass later are repeated | Requirement: Only failures that could pass later are repeated | tier 2 — classification is total over the closed failure family, so a new member cannot default to repeatable | retry decision suite |
| A non-repeatable failure yields exactly one attempt | Scenario: A failure not worth repeating + Property: A failure not worth repeating is attempted exactly once | tier 3 — property test | `non-repeatable-failure-is-attempted-once` |
| The failure reported is the final attempt's | Scenario: The last failure is the one reported | tier 3 — scenario test | retry decision suite |
| Waits grow and respect the ceiling | Requirement: Waiting grows, stays bounded, and is spread out + Property: Waits grow and respect the ceiling | tier 3 — property test | `waits-grow-and-respect-the-ceiling` |
| Jitter removes between none and all of a wait | Scenario: Waits grow and stay under the ceiling | tier 3 — property covering both fraction extremes by construction | `waits-grow-and-respect-the-ceiling` |
| The shipped defaults are the documented ones | Scenario: The shipped defaults + Scenario: The shipped bound | tier 3 — scenario test pinning each default | retry decision suite |
| A service-named wait is preferred unless turned off | Requirement: A wait the service asks for is preferred to a derived one | tier 3 — scenario test; **MUST-CONFIRM**: the headers naming that period are taken from reference-SDK prose, not a published schema (task 12.3) | `service-named-delay-is-preferred` |
| The total bound is never exceeded | Requirement: The whole call is bounded, and a wait that would reach the bound is not begun + Property: The total bound is never exceeded | tier 3 — property test whose generator reaches the first-wait-exceeds-bound case by construction | `total-bound-is-never-exceeded` |
| A bound-stopped call reports the last failure, not a bound failure | Scenario: A wait that would reach the bound | tier 2 — no bound-specific member exists in the failure family to report | retry decision suite |
| Abandoning mid-wait stops further attempts and spends no more bound | Scenario: The caller goes away mid-wait | tier 3 — parity suite on the four rows that can abandon; **tier-justified**: no type can express "this wait can be abandoned" uniformly, because the capability genuinely differs by row | cross-backend parity suite |
| A per-call policy governs only that call | Requirement: A policy may be chosen for one call | tier 2 — the policy is an argument, so no call can mutate the client's own | `per-call-policy-governs-only-that-call` |
| The schedule's arithmetic holds for ALL inputs, not sampled ones | Requirement: Waiting grows, stays bounded, and is spread out + Requirement: The whole call is bounded, and a wait that would reach the bound is not begun | tier 5 — formal contracts on `RetrySchedulerKernel`, discharged by `scripts/ring4.sh` (13/13 valid, `nativez3`) | `verified/src/main/scala/typesafe4s/verified/RetrySchedulerKernel.scala` |
| The kernel describes the code that actually ships | Requirement: Waiting grows, stays bounded, and is spread out + Requirement: The whole call is bounded, and a wait that would reach the bound is not begun | tier 3 — the Ring 2 bridge property; **without it Ring 4 proves nothing about this SDK** | retry bridge property suite |
| The retry tests are strong enough to catch a changed comparison | Requirement: Only failures that could pass later are repeated | tier 3 — Ring 3 mutation over the retry decision and schedule sources, with `stryker4s.conf` retargeted to them | `sbt core/stryker` (retargeted) |
| Timing assertions rest on a deterministic clock | Requirement: Waiting grows, stays bounded, and is spread out | tier 3 — the shared runtime reads time through a `Clock` seam, and tests supply the project's VirtualTime clock, so every wait, ceiling, jitter and bound assertion is deterministic on every row | `DeterministicClockSuite` (9 tests, green on all five rows) + the retry suites built on it |

## Implementation Anchors

The one place in this spec where code identifiers appear.

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `RetryPolicy` | case class, data only | `typesafe4s-core` | Defaults: 2 retries, 0.5s doubling to 5.0s, 0.25 jitter, `{408, 429, 500–599}`, 30s budget |
| Retry loop | shared runtime over the carrier | `typesafe4s-client/shared` | Uses the carrier's sleep, monotonic clock and recovery; names no backend |
| Deterministic clock | **absent** | — | `TestControl` (Cats Effect) / `TestClock` (ZIO) are not on any test classpath; see the capability profile |
| Property suites | munit `ScalaCheckSuite` | `typesafe4s-client/shared/src/test/scala/` | Compiles into every row |
| Parity assertions | shared suite over the carrier | `integration-tests/shared/src/test/scala/` | Run on all five published rows via `sbt parityAll` |
