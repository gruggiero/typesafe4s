# Spec: Error Model

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Evaluation | Evaluation/ask — every way it can fail, and the trace it carries when it does | [evaluation.md](../../../../concepts/evaluation.md) |
| Retry | Retry/classify — this spec provides the distinctions that classification reads | [retry.md](../../../../concepts/retry.md) |
| Credential | Credential/resolve — its absence is one of the failures reported before any request | [credential.md](../../../../concepts/credential.md) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| TypesafeException | sealed hierarchy | Every failure this SDK raises, matchable exhaustively |
| RequestId | optional identifier | The trace the service returns; the only handle support has |

## ADDED Requirements

### Requirement: Every failure belongs to one closed family

The system SHALL raise every failure of Evaluation/ask from one sealed family, so that a caller can
distinguish all of them without a catch-all.

**Given** a caller handles failures from this SDK
**When** they distinguish every member of the family
**Then** no further case is needed
**And** omitting a member is reported before the program runs

**Rationale**: An open hierarchy pushes every caller to a catch-all, which silently absorbs failures
added later. A closed family turns that into a build failure at the moment the family grows.

#### Scenario: Distinguishing every member suffices

**Given** a caller distinguishes each member of the failure family
**When** the program is built
**Then** it is accepted without a catch-all case

#### Scenario: Omitting a member is reported

**Given** a caller omits one member of the family
**When** the program is built
**Then** the omission is reported before the program runs

#### Scenario: A failure still behaves as an ordinary failure

**Given** a failure reaches a caller's general-purpose handler
**When** it is examined
**Then** it presents a readable description of what went wrong

### Requirement: Each documented refusal is its own member

The system SHALL give each documented refusal its own member of the family, so that a caller responds
to them differently without reading status numbers.

**Given** the service refuses a request in a documented way
**When** the failure reaches the caller
**Then** it is the member that names that refusal
**And** the caller need not inspect a status number to tell which

**Rationale**: Every caller writing their own number-to-meaning mapping is the same mapping written many
times and wrong in some of them.

#### Scenario: A refusal the caller caused

**Given** the service refuses the request as malformed, unauthenticated, forbidden, unknown, or unprocessable
**When** the failure reaches the caller
**Then** it is the member naming that specific refusal

#### Scenario: Too many requests

**Given** the service refuses because the caller is sending too many requests
**When** the failure reaches the caller
**Then** it is the rate-limit member
**And** it carries the delay the service named, when the service named one

#### Scenario: Overload is told apart from other service faults

**Given** the service reports itself overloaded
**When** the failure reaches the caller
**Then** it is a member distinct from a general service fault
**And** Retry/classify treats it as worth retrying

#### Scenario: A general service fault

**Given** the service fails in a way it does not further describe
**When** the failure reaches the caller
**Then** it is the general service-fault member
**And** it carries what the service reported

### Requirement: A failure to reach the service is told apart from a refusal by it

The system SHALL distinguish a failure in which no answer was obtained at all from one in which the
service answered by refusing.

**Given** a call fails
**When** the failure is examined
**Then** it says whether the service answered at all

**Rationale**: These call for opposite responses. A refusal is about the request and repeating it
unchanged is pointless; an unreachable service is about the moment and repeating it is the whole remedy.

#### Scenario: The service cannot be reached

**Given** no connection to the service can be established
**When** the failure reaches the caller
**Then** it is the unreachable member
**And** it carries the underlying cause

#### Scenario: An attempt runs out of time

**Given** a single attempt exceeds the time allowed for it
**When** the failure reaches the caller
**Then** it is the timeout member, naming the limit that elapsed
**And** it is not reported as unreachable

### Requirement: A failure carries the trace the service provided

The system SHALL attach the service's own trace to every failure that reached the service, and SHALL
report it as absent rather than as an empty or invented value when the service sent none.

**Given** a call reached the service
**When** its outcome is examined
**Then** the service's trace is available if the service sent one
**And** it is reported as absent if the service sent none

**Rationale**: The trace is the only handle support has. A fabricated or blank one is worse than none,
because it sends someone looking for a record that does not exist.

#### Scenario: A refusal carrying a trace

**Given** the service refuses a request and returns its trace
**When** the failure reaches the caller
**Then** that trace is available on the failure

#### Scenario: A refusal carrying no trace

**Given** the service refuses a request and returns no trace
**When** the failure reaches the caller
**Then** the trace is reported as absent
**And** no blank or invented value appears in its place

#### Scenario: A successful call also carries its trace

**Given** Evaluation/ask succeeds
**When** the outcome is examined
**Then** the service's trace is available alongside the answers

### Requirement: What can be known without asking is failed without asking

The system SHALL raise, without contacting the service, every failure whose outcome is already determined
locally.

**Given** a failure that can be determined without contacting the service
**When** the caller triggers it
**Then** it is raised locally
**And** no request is made

**Rationale**: A round trip that can only end one way costs time and quota to learn what was already known.

#### Scenario: No credential can be resolved

**Given** Credential/resolve finds neither an argument nor an environment value
**When** a client is constructed
**Then** construction fails locally, naming the environment variable to set
**And** no request is made

#### Scenario: Reading an answer that was never asked

**Given** an answer set that does not hold the name a caller reads
**When** that name is read
**Then** a failure naming it is returned
**And** no request is made

## Properties (Ring 2)

### Property: Every documented refusal maps to exactly one member

**Invariant**: Mapping a service refusal to a family member is total over the documented refusals, and
no two documented refusals map to the same member.

**Generator strategy**: `genRefusal` — constructive. Draws from the enumerated documented refusals
(malformed, unauthenticated, forbidden, unknown, unprocessable, too-many-requests, overloaded, and the
general service-fault range), each with a generated body and header set. The set is enumerated rather
than sampled from a numeric range, so every documented case is reached on a short run instead of relying
on a rare draw. `classify` labels: the refusal drawn.

```
forAll { (refusal: Refusal) =>
  val members = documentedRefusals.map(classify)
  members.contains(classify(refusal)) && members.distinct.size == members.size
}
```

### Property: A trace is carried through unchanged or reported absent

**Invariant**: For any outcome, the trace on the result equals the trace the service sent, and is absent
exactly when the service sent none — never blank, never invented.

**Generator strategy**: `genOutcome` — constructive. Draws success or each documented refusal, then
independently draws whether a trace header is present and, if so, its text from an alphabet including
the empty string, so "present but empty" is reached by construction rather than by chance — it is the
case most likely to be mishandled. `classify` labels: outcome kind, trace present/absent/empty.

```
forAll { (outcome: ServiceOutcome) =>
  traceOf(interpret(outcome)) == outcome.traceHeader
}
```

## Requirement ↔ Test Cross-Reference

| Requirement | Test Name (Ring 2) | Test Name (Ring 3) | Ring 5 (parity) |
|-------------|--------------------|--------------------|-----------------|
| Requirement: Every failure belongs to one closed family | `distinguishing every member suffices` + `ErrorModelCompileNegativeSuite` (match-omission, external construction) | `—` | typed-error channel asserted per row — lands with the first raising path (spec 5/6) |
| Requirement: Each documented refusal is its own member | `every-documented-refusal-maps-to-one-member`, `no two documented refusal classes map to the same member`, `a refusal the caller caused`, `too many requests`, `rate-limit-carries-the-service-named-delay-or-none`, `overload is told apart from other service faults`, `a general service fault carries what the service reported` | `—` | `— (mapping is pure)` |
| Requirement: A failure to reach the service is told apart from a refusal by it | `unreachable-is-not-timeout`, `the service cannot be reached`, `an attempt runs out of time` | `—` | asserted per row — lands with the first raising path (spec 5/6) |
| Requirement: A failure carries the trace the service provided | `trace-is-carried-or-absent` (refusal side), `a refusal carrying a trace`, `a refusal carrying no trace reports it absent`, `the trace header is read case-insensitively` | `—` | `— (mapping is pure)`; success row deferred to spec 3 (AnswerSet carries the id) — Gate-0 decision |
| Requirement: What can be known without asking is failed without asking | `local failure members carry the name the caller needs` (member shape); no-request proofs deferred — MissingAnswer read → spec 3, MissingCredential construction → spec 7 (Gate-0 decisions) | `—` | asserted per row (construction differs) — deferred to spec 7 parity run |

## Cross-Backend Parity (Ring 5)

**Applies: YES.** The failure family is defined once in the pure core, but *how a caller receives it*
differs by row, and that difference is part of this spec.

| Behaviour | zio | ce | ox | kyo | pekko | Declared where |
|-----------|-----|----|----|-----|-------|----------------|
| Failures appear in a typed channel naming this family | **yes** | no | no | no | no | this spec + the effect-portability spec |
| A raised failure is a member of this family | yes | yes | yes | yes | yes | this spec |

The ZIO row surfaces the family in its typed error channel; every other row surfaces it as that
ecosystem's ordinary failure. This is a **declared divergence**, not an accident: it is the difference
between a caller who can see the family in the signature and one who must match on it.

**Obligation:** the parity suite asserts, on every row, that a raised failure is a member of this family,
and asserts on the ZIO row specifically that the channel names it.

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| Distinguishing the family while omitting a member | The family is closed, so omission is knowable before the program runs | `-Werror` escalates the E029 exhaustivity warning to a build failure naming the missing member — living witnesses: the exhaustive `memberName` matches in the oracle and typecontract (munit `compileErrors` cannot observe warnings — verified) |
| Constructing a family member from outside the SDK | Members carry service-reported facts; a caller-built one would be a fiction | `compileErrors` |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| The family is closed and omission is reported before the program runs | Requirement: Every failure belongs to one closed family | tier 1 — sealed hierarchy; exhaustiveness is a build concern | error-model compile-negative suite |
| A failure presents a readable description | Scenario: A failure still behaves as an ordinary failure | tier 3 — scenario test over every member | error-model suite |
| Each documented refusal maps to its own member | Requirement: Each documented refusal is its own member + Property: Every documented refusal maps to exactly one member | tier 3 — property test asserting totality and distinctness | `every-documented-refusal-maps-to-one-member` |
| The rate-limit member carries the service-named delay | Scenario: Too many requests | tier 1 — the member carries the delay, optional by construction | error-model type contract |
| Overload is a member distinct from a general service fault | Scenario: Overload is told apart from other service faults | tier 1 — its own member; **MUST-CONFIRM**: the API reference names this refusal but the reference SDK folds it into the general range, so confirm against the service before release | error-model type contract + task 12.3 |
| Unreachable and timeout are different members | Requirement: A failure to reach the service is told apart from a refusal by it | tier 1 — separate members | error-model type contract |
| A trace is carried through or reported absent, never blank or invented | Requirement: A failure carries the trace the service provided + Property: A trace is carried through unchanged or reported absent | tier 1 for optionality + tier 3 for the value | error-model type contract + `trace-is-carried-or-absent` |
| A successful outcome also carries its trace | Scenario: A successful call also carries its trace | tier 1 — the answer set carries it | error-model type contract |
| A missing credential fails before any request | Requirement: What can be known without asking is failed without asking + Scenario: No credential can be resolved | tier 2 — construction refuses; no transport is reachable from that path | `local-failures-make-no-request` |
| Reading an unasked name makes no request | Scenario: Reading an answer that was never asked | tier 2 — lookup is a pure read over an answer set already in hand | `local-failures-make-no-request` |
| Every row raises a member of this family; only ZIO names it in the channel | Requirement: Every failure belongs to one closed family | tier 3 — parity suite, executed on all five rows | cross-backend parity suite |

## Implementation Anchors

The one place in this spec where code identifiers appear.

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `TypesafeException` | sealed trait | `typesafe4s-core`, package `typesafe4s` | Members: `BadRequest`, `Authentication`, `PermissionDenied`, `NotFound`, `UnprocessableEntity`, `RateLimit`, `Overloaded`, `InternalServer`, `ResponseValidation`, `ConnectionFailed`, `Timeout`, `MissingAnswer`, `MissingCredential`. Trait (not abstract class): a constructor-args shape leaves a legal anonymous-subclass hole and the omission guarantee never fires — Gate-1 correction |
| `MissingCredential` | member | `typesafe4s-core` | Raised by construction when Credential/resolve finds neither an argument nor an environment value; carries the environment variable to set (`TYPESAFE_API_KEY`) |
| `Overloaded` | member | `typesafe4s-core` | Distinct member for the 529 refusal — a deliberate superset of the reference SDK's hierarchy |
| `RateLimit.retryAfter` | optional duration | `typesafe4s-core` | Fed from the service's own delay headers; header names are MUST-CONFIRM (task 12.3) |
| Request id | optional value on answers and failures | `typesafe4s-core` | Read from the `x-typesafe-request-id` response header |
| ZIO typed channel | facade type alias | `typesafe4s-client/zio` | `refineToOrDie` narrows to this family; the other rows keep their ordinary channel |
| Property suites | munit `ScalaCheckSuite` | `typesafe4s-core/src/test/scala/` | munit-scalacheck 1.3.1 |
| Parity assertions | shared suite over the carrier | `integration-tests/shared/src/test/scala/` | Recompiled and run on all five published rows |
