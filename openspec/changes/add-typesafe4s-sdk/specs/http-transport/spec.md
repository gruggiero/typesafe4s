# Spec: HTTP Transport

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Evaluation | Evaluation/ask — this spec carries its request out and its answer back | [evaluation.md](../../../../concepts/evaluation.md) |
| Credential | Credential/present — every exchange carries the authorization it produces | [credential.md](../../../../concepts/credential.md) |
| Retry | Retry/schedule — an exchange abandoned mid-flight must stop its retry loop too | [retry.md](../../../../concepts/retry.md) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| Transport | single-method capability | Exchanges a request for a response; the seam a caller may replace |
| HttpRequest / HttpResponse | product types, data only | Method, target, headers, body — defined in the pure core |

## ADDED Requirements

### Requirement: The pure core describes exchanges but performs none

The system SHALL define requests and responses as data in the pure core, and SHALL perform every
exchange through a single replaceable capability.

**Given** the pure core is examined
**When** its contents are read
**Then** it describes requests and responses
**And** it reaches no network itself

**Rationale**: Keeping the description separate from the act is what lets the description be tested
without a network and the act be replaced without touching the description.

#### Scenario: The core performs no exchange

**Given** the pure core
**When** it is examined
**Then** nothing in it reaches a network

#### Scenario: A caller supplies their own exchange

**Given** a caller supplies their own implementation of the capability
**When** Evaluation/ask runs
**Then** every exchange goes through the supplied implementation
**And** nothing else in the SDK needed changing

#### Scenario: Exercising the SDK with no network

**Given** a stand-in implementation that answers from a prepared script
**When** the SDK is exercised against it
**Then** repeating, failure mapping and reading are all exercised
**And** no network is reached

### Requirement: Using the SDK requires no third-party exchange library

The system SHALL provide a default exchange built on what the platform already offers.

**Given** a caller who configured no exchange
**When** Evaluation/ask runs
**Then** the platform-provided default is used
**And** no third-party exchange library is drawn onto their path

**Rationale**: Most callers want the SDK to work on arrival. Those with opinions about proxies, mutual
authentication or tracing already have a library, and the previous requirement lets them bring it.

#### Scenario: The default is used when none is chosen

**Given** a client constructed without choosing an exchange
**When** a call is made
**Then** the platform-provided default performs it

#### Scenario: An exchange carries what the service requires

**Given** a request about to be sent
**When** its headers are examined
**Then** it carries the authorization Credential/present produced
**And** it declares the body it is sending
**And** it carries any headers the client was configured with

#### Scenario: A per-call header wins over a configured one

**Given** a client configured with a header, and a call supplying the same header
**When** the request is sent
**Then** the value supplied for the call is the one sent

### Requirement: Abandoning a call abandons the exchange in flight

The system SHALL stop the exchange in flight when the caller abandons the call, rather than leaving it
running to completion unobserved.

**Given** an exchange is in flight
**When** the caller abandons the call
**Then** the exchange itself is stopped
**And** it is not left running with nobody waiting for it

**Rationale**: The mechanism this SDK builds on does **not** pass abandonment through on its own. Left
alone it would observe the abandonment while the exchange kept running — holding a connection, spending
the caller's quota, for an answer nobody will read.

#### Scenario: Abandoning mid-exchange

**Given** an exchange is in flight on a row that supports abandonment
**When** the caller abandons the call
**Then** the exchange is stopped
**And** the connection is not left running to completion

#### Scenario: Abandoning stops the repeat loop too

**Given** a call is waiting before its next attempt
**When** the caller abandons it
**Then** no further attempt is made
**And** Retry/schedule spends no more of the bound

#### Scenario: A row that cannot abandon says so

**Given** a row whose foundation has no notion of abandoning work
**When** the caller abandons the call
**Then** the documented behaviour is that nothing is stopped
**And** that limitation is stated rather than hidden behind a uniform surface

### Requirement: Each attempt is given its own time allowance

The system SHALL apply the configured allowance to each attempt separately, leaving the whole call
bounded by Retry/schedule.

**Given** a call is made
**When** each attempt runs
**Then** each is given the full configured allowance
**And** the call as a whole remains bounded by the total budget

**Rationale**: An allowance spread across attempts would shrink each one until a later attempt could not
succeed at all, which is the opposite of what repeating is for.

#### Scenario: An attempt runs out of time

**Given** an attempt exceeds its allowance
**When** it is abandoned
**Then** it fails naming the allowance that elapsed
**And** it is eligible to be tried again

#### Scenario: Several attempts each get the full allowance

**Given** a call that makes three attempts
**When** each runs
**Then** each is given the full allowance
**And** the total is still bounded by the budget for the whole call

## Properties (Ring 2)

### Property: Every request carries its required headers

**Invariant**: For any configuration and any call, the request carries authorization and a body
declaration, and for any header named in both places the per-call value is the one sent.

**Generator strategy**: `genHeaderSetup` — constructive. Draws a configured header map and a per-call
header map from a shared small key alphabet, so overlap between the two happens on most runs rather than
rarely — overlap is the case the requirement is about. Includes the empty map on both sides so the
no-overlap and no-headers cases are reachable. `classify` labels: overlap count, empty/non-empty on each side.

```
forAll { (configured: Headers, perCall: Headers) =>
  val sent = requestFor(configured, perCall).headers
  sent.contains(authorization) && sent.contains(contentType) &&
    configured.keys.intersect(perCall.keys).forall(k => sent(k) == perCall(k))
}
```

### Property: An abandoned call performs no further exchange

**Invariant**: For any call abandoned at any point, the number of exchanges begun after the moment of
abandonment is zero.

**Generator strategy**: `genAbandonPoint` — constructive. Uses the stand-in exchange to make each attempt
block until released, then abandons at a drawn point: before the first exchange, during an exchange,
during a wait, or after the last attempt. Enumerated rather than timed, so every point is reached
deterministically and the property does not depend on wall-clock racing. `classify` labels: the point drawn.

```
forAll { (point: AbandonPoint) =>
  val run = simulateAbandon(point)
  run.exchangesBegunAfter(point) == 0
}
```

## Requirement ↔ Test Cross-Reference

| Requirement | Test Name (Ring 2) | Test Name (Ring 3) | Ring 5 (parity) |
|-------------|--------------------|--------------------|-----------------|
| Requirement: The pure core describes exchanges but performs none | `core-reaches-no-network` | `—` | `— (core compiles once)` |
| Requirement: Using the SDK requires no third-party exchange library | `every-request-carries-required-headers` | `—` | asserted per row |
| Requirement: Abandoning a call abandons the exchange in flight | `abandoned-call-performs-no-further-exchange` | `—` | **declared divergence — see below** |
| Requirement: Each attempt is given its own time allowance | `each-attempt-gets-the-full-allowance` | `—` | asserted per row |

## Cross-Backend Parity (Ring 5)

**Applies: YES.** The exchange and its abandonment live in the shared client and compile into every row,
and abandonment is precisely where ecosystems differ.

| Behaviour | zio | ce | ox | kyo | pekko | Declared where |
|-----------|-----|----|----|-----|-------|----------------|
| Headers and per-call precedence | yes | yes | yes | yes | yes | this spec |
| Per-attempt allowance | yes | yes | yes | yes | yes | this spec |
| Abandoning stops the exchange in flight | yes | yes | yes | yes | **no** | this spec |

**Obligation:** the parity suite asserts, per abandoning row, that (a) abandoning stops the exchange in
flight, (b) abandoning during a wait makes no further attempt, and (c) the remaining budget is not spent
afterwards. Those are three separate assertions: a test covering only the first leaves the retry loop
burning budget for a caller who has gone.

On the Pekko row the suite asserts the **documented no-op** rather than skipping the case, so the
divergence stays visible in the results.

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| Reaching a network from the pure core | The core is described as performing no exchange; the capability is not on its path | `compileErrors` on referencing the capability from core |
| Sending a request with no authorization | Every exchange carries what Credential/present produced | `compileErrors` on constructing one without it |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| The pure core reaches no network | Requirement: The pure core describes exchanges but performs none | tier 1 — the capability is not on the core's path, so referencing it does not build | http-transport compile-negative suite |
| A supplied exchange is used for every call | Scenario: A caller supplies their own exchange | tier 1 — the only path to an exchange is through the capability | http-transport type contract |
| The SDK is exercisable with no network | Scenario: Exercising the SDK with no network | tier 3 — the stand-in exchange, used by the retry, failure-mapping and reading suites | stand-in exchange, shared test support |
| Every request carries authorization and a body declaration | Requirement: Using the SDK requires no third-party exchange library + Property: Every request carries its required headers | tier 3 — property test | `every-request-carries-required-headers` |
| A per-call header wins over a configured one | Scenario: A per-call header wins over a configured one | tier 3 — property test reaching overlap by construction | `every-request-carries-required-headers` |
| Abandoning stops the exchange in flight | Requirement: Abandoning a call abandons the exchange in flight | tier 3 — parity suite per abandoning row; **tier-justified**: abandonment is a runtime capability that genuinely differs by row, so no single type can express it | cross-backend parity suite |
| Abandoning during a wait makes no further attempt | Scenario: Abandoning stops the repeat loop too + Property: An abandoned call performs no further exchange | tier 3 — property test with enumerated abandon points | `abandoned-call-performs-no-further-exchange` |
| A row that cannot abandon documents the no-op | Scenario: A row that cannot abandon says so | tier 3 — parity suite asserts the documented behaviour rather than skipping | cross-backend parity suite |
| Each attempt gets the full allowance | Requirement: Each attempt is given its own time allowance | tier 3 — scenario test over a multi-attempt call | `each-attempt-gets-the-full-allowance` |
| An attempt that runs out of time may be tried again | Scenario: An attempt runs out of time | tier 2 — the timeout failure is a member Retry/classify repeats | retry decision suite |
| Timing and abandonment assertions rest on a deterministic clock | Requirement: Each attempt is given its own time allowance | tier 3 — the per-attempt allowance is measured against the `Clock` seam, with the project's VirtualTime clock supplied in tests; abandonment is already asserted by enumerated points rather than by timing | `DeterministicClockSuite` + the transport suites built on it |

## Implementation Anchors

The one place in this spec where code identifiers appear.

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `HttpRequest` / `HttpResponse` | case classes, data only | `typesafe4s-core` | No I/O; keeps core dependency-free |
| `Transport` | trait, one method over the carrier | `typesafe4s-client/shared` | Public seam: implement one method to use http4s, sttp or zio-http |
| `JdkHttpTransport` | default implementation | `typesafe4s-client/shared` | Built on `java.net.http.HttpClient`; adds no dependency |
| Abandonment bracket | acquire/release around the in-flight exchange | `typesafe4s-client/shared` | `kyo.compat`'s `fromCompletionStage` does **not** pass cancellation through — the bracket calls `cancel(true)`, which the JDK client honours by aborting |
| Stand-in exchange | test support | `typesafe4s-client/shared/src/test/scala/` | Scripted responses; compiles into every row |
| Parity assertions | shared suite over the carrier | `integration-tests/shared/src/test/scala/` | `sbt parityAll` |
