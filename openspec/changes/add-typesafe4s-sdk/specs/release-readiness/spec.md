# Spec: Release Readiness

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Credential | Credential/resolve — the environment variable that gates live coverage | [credential.md](../../../../concepts/credential.md) |
| Evaluation | Evaluation/ask, Evaluation/listJudges — the real calls the live suite exercises | [evaluation.md](../../../../concepts/evaluation.md) |
| Token Budget | TokenBudget/estimate — the conservative margin confirmed against real accounting | [token-budget.md](../../../../concepts/token-budget.md) |

## Concepts Introduced (new)

None. This spec adds release machinery — live coverage, examples, documentation, CI — around concepts
that already exist. Nothing here is a new behaviour the SDK performs.

## ADDED Requirements

### Requirement: A real exchange is exercised when a credential is present

The system SHALL carry a suite that performs a real Evaluation/ask against the live service for every
published backend, gated on Credential/resolve's documented environment variable: absent, the suite is
skipped; present, it runs.

**Given** the live suite is invoked
**When** the documented credential variable is absent from the environment
**Then** every live test is skipped
**And** no test fails

**Rationale**: Wire facts asserted from documentation alone are hypotheses. A suite that runs against
the real service turns them into observations — but a suite that fails without a key would make every
contributor build and every CI run red for a credential they legitimately lack.

#### Scenario: No credential, no failure

**Given** no credential is configured
**When** the full test run completes
**Then** the live tests report as skipped
**And** the run is green

#### Scenario: A real exchange per backend

**Given** the credential variable holds a live key
**When** the live suite runs
**Then** each published backend performs one real evaluation
**And** the returned answers and usage are observable

### Requirement: The documented wire facts are confirmed against the live service

The system SHALL reconcile, against real responses, the wire facts asserted from documentation: the
header names carrying retry delay advice, the semantics of the overloaded status, the body shape of
the models listing, and the margin between Token Budget/estimate and the service's reported
accounting. A fact that differs SHALL be reconciled by amending the codec or the spec that asserts it.

**Given** a live credential
**When** the reconciliation is performed
**Then** each documented wire fact is confirmed or amended
**And** any correction is recorded in the spec that asserts the fact

**Rationale**: These four facts were written from prose, never observed (carried as MUST-CONFIRM
through every spec that asserts them). Each is built to fail loudly when wrong — strict decode,
explicit error members, a deliberately conservative estimate — but "fails loudly" is only true if
someone eventually makes it fail or watches it pass.

#### Scenario: Retry delay advice matches its header names

**Given** a live response carrying the rate-limit status
**When** its headers are read
**Then** the delay advice the client honours is the advice the service sent
**And** a differently-named header is either also honoured or recorded as absent

#### Scenario: The overloaded status behaves as documented

**Given** a live response carrying the overloaded status, or evidence the service never emits it
**When** its classification is checked
**Then** the distinct retryable member is confirmed
**Or** the error model and spec are corrected if the service does not emit it

#### Scenario: The models listing decodes against the real body

**Given** a live listing response
**When** the strict decoder reads it
**Then** decoding succeeds
**Or** the codec and its fixtures are corrected to the real shape

#### Scenario: The estimate does not understate real accounting

**Given** a set of live exchanges with varied state and question sizes, including non-ASCII content
**When** each response's reported accounting is compared to the local estimate
**Then** the estimate does not understate the reported accounting
**Or** the divisor and the spec are corrected until it does not

### Requirement: Every published backend has a runnable example

The system SHALL carry, for each published backend, a runnable example mirroring the documented
quickstart, plus a confidence-gated routing example, and the examples SHALL be compiled in CI.

**Given** a caller evaluating the SDK on their backend
**When** they look for a starting point
**Then** an example exists that runs their backend's own idioms
**And** it exercises a real question set end to end against a stub or live service

**Rationale**: The per-row surface is the SDK's main claim — the same call expressed in each
ecosystem's own effect and stream types. An example that only exists for one row leaves the claim
unverified for the others, and an example that cannot compile rots silently.

#### Scenario: A quickstart per backend

**Given** a published backend row
**When** its example directory is read
**Then** an example constructs the client, asks a question set, and consumes the answers in that
backend's own idiom

#### Scenario: A confidence-gated routing example

**Given** the examples
**When** the routing example is read
**Then** it shows an answer's confidence driving a routing decision

#### Scenario: Examples cannot rot

**Given** a change to any public surface
**When** CI runs
**Then** every example still compiles

### Requirement: The README states what a new caller needs

The system SHALL carry a README that names the installation coordinates with their groupId, states
the disambiguation from Typesafe Inc. (now Lightbend), shows the quickstart, and records which
backends ship at 0.1.

**Given** a caller who has never seen the project
**When** they read the README
**Then** they can name the dependency to add
**And** they can tell this SDK is not affiliated with Lightbend
**And** they can see which backends are supported

**Rationale**: The groupId is not `com.typesafe` — that is Lightbend's. A README that lets a caller
guess wrong coordinates, or mistake the affiliation, fails before the code is ever reached. The 0.1
backend scope decision (design open question 1) belongs where a caller will read it, not only in
design history.

#### Scenario: The groupId is stated and is not Lightbend's

**Given** the README's installation section
**When** the coordinates are read
**Then** the groupId is the project's own
**And** the disambiguation is present

#### Scenario: The backend scope is recorded

**Given** the README
**When** the supported-backends section is read
**Then** it names the backends shipping at 0.1 — the decision of design open question 1, made visible

### Requirement: Every row is built, tested and linted in CI

The system SHALL run, on every push, a build that compiles, tests and lints every published row and
the core, and SHALL publish snapshots via the release plugin on tagged releases.

**Given** a push or a tag
**When** CI runs
**Then** every row is compiled and tested
**And** a tag publishes snapshots for every published artifact
**And** the unpublished anchor row is never published

**Rationale**: Five backend rows sharing one source tree are the architecture's strength and its CI
risk — a row broken by a shared change is invisible until someone builds that row. The unpublished
anchor row existing for development must never leak into the published set.

#### Scenario: A broken row fails the build

**Given** a change that breaks one backend row
**When** CI runs
**Then** the build fails, naming that row

#### Scenario: A tag publishes exactly the published set

**Given** a tagged release
**When** publishing completes
**Then** snapshots exist for the core, every published client row, and every adapter
**And** no artifact exists for the unpublished anchor row

## Properties (Ring 2)

No `forAll` properties arise. Every obligation in this spec is a build or release fact — a file's
existence, a CI matrix's coverage, a live reconciliation's verdict — discharged by tier-4 assertions
or one-off scenario checks, not by a property over generated inputs. The property sections of the
behavioural specs remain the sole Ring-2 surface.

## Requirement ↔ Test Cross-Reference

| Requirement | Test Name (Ring 2) | Test Name (Ring 3) | Ring 5 (parity) |
|-------------|--------------------|--------------------|-----------------|
| Requirement: A real exchange is exercised when a credential is present | `live-suite-skips-without-credential` + `live-exchange-per-row` | `—` | **asserted per row — each row runs its own live exchange** |
| Requirement: The documented wire facts are confirmed against the live service | `live-wire-fact-reconciliation` (skipped absent key) | `—` | `— (asserted once against the service)` |
| Requirement: Every published backend has a runnable example | `example-compiles` × 5 | `—` | **asserted per row — examples are per-row artifacts** |
| Requirement: The README states what a new caller needs | `readme-states-coordinates-and-scope` | `—` | `— (single document)` |
| Requirement: Every row is built, tested and linted in CI | `ci-covers-every-row` + `publish-set-excludes-anchor` | `—` | **asserted per row — the matrix IS the parity check** |

## Cross-Backend Parity (Ring 5)

**Applies: YES.** The live suite and the examples exist per published row — that is the parity table.

| Behaviour | zio | ce | ox | kyo | pekko | Declared where |
|-----------|-----|----|----|-----|-------|----------------|
| Live exchange runs when credentialed | yes | yes | yes | yes | yes | this spec |
| Quickstart example exists and compiles | yes | yes | yes | yes | yes | this spec |
| CI compiles, tests and lints the row | yes | yes | yes | yes | yes | this spec |

No divergence is expected: every row already runs the shared suite in CI-equivalent local runs, and
the per-row idiom differences (which the live suite and examples must express in each row's own
types) are the point, not a deviation.

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| — none arise | The forbidden constructions here are build facts (a row missing from CI, the anchor row in the publish set), not type-level constructions | enforced as build assertions instead |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| The live suite skips without a credential and never fails the run | Requirement: A real exchange is exercised when a credential is present | tier 3 — scenario test asserting skip-not-fail | `live-suite-skips-without-credential` |
| Each published row performs a real exchange when credentialed | Scenario: A real exchange per backend | tier 3 — live suite per row, gated; **tier-justified**: a live service cannot be a tier-1 type fact | `live-exchange-per-row` × 5 |
| Each documented wire fact is confirmed or amended | Requirement: The documented wire facts are confirmed against the live service | tier 3 — the reconciliation record itself; MUST-CONFIRM until a key exists (task 12.3) | live reconciliation record |
| Retry-after header names match the live service | Requirement: The documented wire facts are confirmed against the live service + Scenario: Retry delay advice matches its header names | tier 3 — live reconciliation; MUST-CONFIRM until a key exists (task 12.3) | live reconciliation record |
| The overloaded status is confirmed or the model corrected | Scenario: The overloaded status behaves as documented | tier 3 — live reconciliation; MUST-CONFIRM until observed | live reconciliation record |
| The models listing shape is confirmed or corrected | Scenario: The models listing decodes against the real body | tier 3 — live reconciliation; **CORRECTED 2026-09-19**: live body is `{"models":[{"name","description","release_date"}]}` (was `{"data":[{"id","display_name","created_at"}]}`) — `Models.decodeResponse` corrected | live reconciliation record |
| The estimate does not understate real accounting | Scenario: The estimate does not understate real accounting | tier 3 — live comparison against reported usage; **CORRECTED 2026-09-19**: the service counts a fixed ~250-token scaffold the request never carries — `TokenBudget.estimate` gained `ServiceOverhead = 512` + `ceil(bytes/3)` (was `bytes/3`, understated small requests ~3.7×) | live reconciliation record |
| Every row has a compiling quickstart example | Requirement: Every published backend has a runnable example | tier 4 — the examples are build modules; a non-compiling example fails the build | example modules in CI |
| The routing example gates on confidence | Scenario: A confidence-gated routing example | tier 3 — example exists and is read by review; compile keeps it honest | routing example source |
| The README states coordinates, disambiguation and backend scope | Requirement: The README states what a new caller needs | tier 4 — release checklist assertion over README contents | `readme-states-coordinates-and-scope` |
| The 0.1 backend scope decision is recorded | Scenario: The backend scope is recorded | tier 4 — README content; resolves design open question 1 | README supported-backends section |
| CI covers every row and the publish set excludes the anchor | Requirement: Every row is built, tested and linted in CI | tier 4 — CI matrix definition + publish configuration; **tier-justified**: a CI matrix is a build fact no type can express | CI workflow + publish assertion |

## Implementation Anchors

The one place in this spec where code identifiers appear.

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| Live suite | munit suite | `integration-tests/live/` or per-row `src/liveTest` | Gated on `TYPESAFE_API_KEY` (config constant `apiKeyEnv`); skipped via munit's tag/assume when absent |
| Reconciliation record | ledger entries | `state.md` + the specs asserting each fact | Corrects codecs/fixtures where the live service differs (task 12.3) |
| Examples | modules or `examples/` tree | one per published row + routing example | Compiled by CI so they cannot rot |
| README | document | repo root | groupId note, Lightbend disambiguation, quickstart, backend scope (design open question 1) |
| CI workflow | GitHub Actions | `.github/workflows/` | Matrix over the five published rows + core; `sbt-ci-release` publishes snapshots on tags |
| Publish assertion | build check | `build.sbt` | The `future` anchor row must never appear in the published set |
