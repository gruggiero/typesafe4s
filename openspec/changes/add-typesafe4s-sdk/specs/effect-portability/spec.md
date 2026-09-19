# Spec: Effect Portability

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Evaluation | Evaluation/ask — the operation whose result type differs per ecosystem while its behaviour must not | [evaluation.md](../../../../concepts/evaluation.md) |
| Retry | Retry/schedule — one of the hard behaviours that must exist once, not five times | [retry.md](../../../../concepts/retry.md) |
| TokenBudget | TokenBudget/split — likewise shared, with only its stream shape adapted per ecosystem | [token-budget.md](../../../../concepts/token-budget.md) |

> **Effect portability is deliberately not itself a concept.** It has no state and no actions of its own;
> it changes the type every action returns. `openspec/concepts/README.md` records that decision. This spec
> is where it is specified instead.

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| Carrier | type that resolves differently per published artifact | How a shared implementation names an effect without naming an ecosystem |
| Facade | per-ecosystem adapter | Supplies two conversions; everything else is derived from them |

## ADDED Requirements

### Requirement: The shared implementation names no ecosystem

The system SHALL express every shared behaviour in terms of the carrier alone, so that Evaluation/ask,
Retry/schedule and TokenBudget/split each exist once rather than once per ecosystem.

**Given** the shared sources are examined
**When** they are read
**Then** no ecosystem's own types appear in them
**And** every effect is expressed through the carrier

**Rationale**: The moment shared code names one ecosystem, that behaviour has to be written again for
each of the others — and the copies diverge, because the hard ones are hard in different ways each time.

#### Scenario: Shared sources name no ecosystem

**Given** the shared sources
**When** they are read
**Then** none of the five ecosystems' types is referenced

#### Scenario: Each hard behaviour exists once

**Given** repeating, abandoning, dividing and reading
**When** each is located
**Then** exactly one implementation exists, in the shared sources
**And** no facade restates it

#### Scenario: A caller never meets the carrier

**Given** a caller using any published artifact
**When** any operation's type is examined
**Then** it is that ecosystem's own type
**And** the carrier does not appear

**Declared seam**: the `Transport` interface a caller implements to plug in a custom transport is
carrier-facing by design — the http-transport spec made it public so an implementation written once
against the carrier serves every artifact. This is an interface the caller *implements*, not a type an
operation *returns*; no published operation's signature exposes the carrier.

### Requirement: Each ecosystem gets its own published artifact

The system SHALL publish one artifact per supported ecosystem, compiled from the shared sources, so a
caller depends only on the one matching what they already use.

**Given** a release is made
**When** the published artifacts are listed
**Then** one exists per supported ecosystem
**And** all are compiled from the same shared sources

**Rationale**: A caller who must translate at every call pays for the translation at every call, and
loses abandonment across the boundary.

#### Scenario: The published set

**Given** a release
**When** its artifacts are listed
**Then** one is published for each of the five supported ecosystems

#### Scenario: The baseline row is not published

**Given** a release
**When** its artifacts are listed
**Then** the compile-only baseline row is absent from them

#### Scenario: Each artifact declares what it needs

**Given** a published artifact for one ecosystem
**When** its declared dependencies are read
**Then** that ecosystem's own libraries are named explicitly
**And** they are not left to arrive indirectly

### Requirement: An ecosystem is adapted by supplying two conversions

The system SHALL require of each facade only a conversion from the carrier into that ecosystem's effect
and one back, deriving every other operation from those two.

**Given** a new ecosystem is to be supported
**When** its facade is written
**Then** supplying those two conversions is sufficient
**And** no shared behaviour is rewritten for it

**Rationale**: If adapting an ecosystem took more than that, the additional work would be where the
ecosystems silently diverge.

#### Scenario: Adding an ecosystem

**Given** an ecosystem not yet supported
**When** its facade supplies the two conversions
**Then** every shared operation is available on it
**And** nothing shared needed changing

#### Scenario: Streaming is adapted, not reimplemented

**Given** an operation that yields a stream
**When** a facade adapts it
**Then** it adapts the shape only
**And** the logic producing the elements stays shared

### Requirement: Failures reach ZIO callers in a named channel

The system SHALL surface failures in the typed channel of the ecosystem that has one, and as that
ecosystem's ordinary failure elsewhere, in both cases as a member of the SDK's closed family.

**Given** a caller using an ecosystem with a typed failure channel
**When** an operation's type is examined
**Then** the channel names the SDK's failure family
**And** on every other ecosystem, failures are still members of that family

**Rationale**: Where an ecosystem can show the failures in the signature, hiding them behind a general
failure type discards information the caller could have used.

#### Scenario: A typed channel where one exists

**Given** a caller using the ecosystem with a typed failure channel
**When** an operation's type is examined
**Then** the SDK's failure family is named in it

#### Scenario: Ordinary failures elsewhere

**Given** a caller using any other supported ecosystem
**When** an operation fails
**Then** the failure arrives through that ecosystem's ordinary means
**And** it is a member of the SDK's failure family

### Requirement: An operation missing from one artifact fails the build

The system SHALL fail the build when an operation is offered by some published artifacts and not others.

**Given** an operation exists on some published artifacts and not others
**When** the build runs
**Then** it fails
**And** it names the operation that is missing

**Rationale**: Adding an operation to one facade and forgetting another produces an SDK that is
inconsistent in a way no reviewer reliably catches and no caller discovers until they switch.

#### Scenario: An operation left off one artifact

**Given** an operation added to one facade and omitted from another
**When** the build runs
**Then** it fails, naming the missing operation

#### Scenario: The check is shown to be capable of failing

**Given** the completeness check
**When** an operation is removed from one facade on purpose
**Then** the build fails
**And** restoring the operation makes it pass again

### Requirement: Behaviour is shown to agree on every artifact

The system SHALL exercise one shared behavioural suite on every published artifact, and SHALL record each
artifact's result separately.

**Given** a behavioural suite written once against the carrier
**When** it is exercised
**Then** it runs on every published artifact
**And** each artifact's result is recorded on its own

**Rationale**: The shared sources being identical proves nothing about behaviour: the carrier resolves to
a different implementation in each artifact. Only running them shows they agree.

#### Scenario: The suite runs everywhere

**Given** the shared behavioural suite
**When** it is exercised
**Then** it runs on each of the five published artifacts
**And** each result is recorded separately

#### Scenario: A result not obtained is not a result

**Given** an artifact whose suite was not run
**When** the outcome is reported
**Then** that artifact is reported as not run
**And** it is not reported as passing

#### Scenario: A genuine difference is written down

**Given** a behaviour that genuinely differs between ecosystems
**When** the difference is found
**Then** it is stated in the specification and in the published documentation
**And** it is asserted per artifact rather than hidden behind a uniform surface

## Properties (Ring 2)

### Property: Lowering then lifting preserves the result

**Invariant**: For any value or failure carried, converting into an ecosystem's effect and back yields the
same outcome — the same value, or the same failure.

**Generator strategy**: `genOutcome` — constructive. Draws a success carrying a value from a small
alphabet, or a failure drawn from the enumerated members of the SDK's failure family — enumerated so every
member crosses the conversion on a short run. Compiled into every row, so the same property runs against
each ecosystem's own conversions. `classify` labels: success or which failure member.

```
forAll { (outcome: Outcome) =>
  runOnThisRow(lift(lower(carry(outcome)))) == runOnThisRow(carry(outcome))
}
```

### Property: The carrier composes the same way on every artifact

**Invariant**: Composing carried computations yields the same observable result regardless of which
artifact is running it.

**Generator strategy**: `genProgram` — constructive. Draws a small program from an enumerated set of
carrier operations (lift a value, map, chain, fail, recover) up to a bounded depth, with the expected
result computed independently from the drawn structure rather than from running it — so the property
compares against a model, not against itself. `classify` labels: program depth, whether it fails.

```
forAll { (program: CarrierProgram) =>
  runOnThisRow(interpret(program)) == expectedOf(program)
}
```

## Requirement ↔ Test Cross-Reference

| Requirement | Test Name (Ring 2) | Test Name (Ring 3) | Ring 5 (parity) |
|-------------|--------------------|--------------------|-----------------|
| Requirement: The shared implementation names no ecosystem | `shared-sources-name-no-ecosystem` | `—` | inherent — shared sources compile into every row |
| Requirement: Each ecosystem gets its own published artifact | `published-artifacts-match-the-declared-set` | `—` | build assertion per row |
| Requirement: An ecosystem is adapted by supplying two conversions | `lowering-then-lifting-preserves-the-result` | `—` | run on every row |
| Requirement: Failures reach ZIO callers in a named channel | compile-negative on the typed channel | `—` | asserted per row |
| Requirement: An operation missing from one artifact fails the build | `completeness-check-fails-when-an-operation-is-removed` | `—` | the check itself is the parity mechanism |
| Requirement: Behaviour is shown to agree on every artifact | `carrier-composes-the-same-way` | `—` | **this requirement is Ring 5** |

## Cross-Backend Parity (Ring 5)

**Applies: YES — this spec is where the ring is defined.** Every other spec's parity obligation rests on
the mechanisms required here.

| Behaviour | zio | ce | ox | kyo | pekko | Declared where |
|-----------|-----|----|----|-----|-------|----------------|
| Shared behaviours exist once and run identically | yes | yes | yes | yes | yes | this spec |
| Lowering and lifting round-trip values and failures | yes | yes | yes | yes | yes | this spec |
| Failures named in a typed channel | **yes** | no | no | no | no | this spec + error-model spec |
| Abandoning work is possible at all | yes | yes | yes | yes | **no** | this spec + http-transport spec |

Two divergences are **declared, not accidental**: only one ecosystem has a typed failure channel, and one
has no notion of abandoning work. Both are properties of the ecosystems, and stating them is more useful
than a uniform surface that quietly behaves differently on one row.

**Obligations:**
1. The shared behavioural suite runs on all five published artifacts, each result recorded separately. A
   row not run is reported as not run — never as passing.
2. The completeness check is shown to be capable of failing, by removing an operation from one facade,
   observing the build fail, and restoring it. A check nobody has seen fail has not been tested.
3. The compile-only baseline row is compiled, not run, and is recorded as such.

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| Referring to an ecosystem's own type from the shared sources | Shared code must name no ecosystem | `compileErrors` in a shared-source test |
| Exposing the carrier in a published operation's type | A caller must meet only their own ecosystem's types | `compileErrors` on a facade returning the carrier |
| Using an operation present on one artifact and absent on another | The completeness check makes the omission a build failure | `compileErrors` in the completeness check |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Shared sources name no ecosystem | Requirement: The shared implementation names no ecosystem | tier 1 — no ecosystem library is on the shared sources' path, so referencing one does not build | effect-portability compile-negative suite |
| Each hard behaviour exists exactly once | Scenario: Each hard behaviour exists once | tier 8 — adversarial review against the diff; **tier-justified**: "exactly one implementation" is a property of the whole source tree, which no type and no single test can express | Ring 8 review record |
| The carrier never appears in a published operation's type | Scenario: A caller never meets the carrier | tier 1 — facades return ecosystem types; a carrier-returning operation does not build. The caller-*implemented* `Transport` SPI is the declared carrier-facing seam | effect-portability compile-negative suite |
| One artifact is published per ecosystem, and the baseline is not | Requirement: Each ecosystem gets its own published artifact | tier 4 — build assertion over the published set and the publication flags | build assertions |
| Each artifact declares its ecosystem's libraries explicitly | Scenario: Each artifact declares what it needs | tier 4 — build assertion per row's declared dependencies | build assertions |
| Two conversions suffice to adapt an ecosystem | Requirement: An ecosystem is adapted by supplying two conversions | tier 1 — the facade's obligations are exactly those two; everything else is final in the shared adapter | effect-portability type contract |
| Lowering then lifting preserves values and failures | Requirement: An ecosystem is adapted by supplying two conversions + Property: Lowering then lifting preserves the result | tier 3 — property test compiled into and run on every row | `lowering-then-lifting-preserves-the-result` |
| Streaming facades adapt shape only | Scenario: Streaming is adapted, not reimplemented | tier 8 — adversarial review; **tier-justified**: "adapts rather than reimplements" is a judgement about structure, not a checkable value | Ring 8 review record |
| Failures are named in the typed channel where one exists | Requirement: Failures reach ZIO callers in a named channel | tier 1 — the facade's type alias names the family | effect-portability type contract |
| Failures are members of the family on every ecosystem | Scenario: Ordinary failures elsewhere | tier 3 — parity suite on all five rows | cross-backend parity suite |
| A missing operation fails the build | Requirement: An operation missing from one artifact fails the build | tier 1 — the completeness check does not build when an operation is absent | completeness check |
| The completeness check is shown capable of failing | Scenario: The check is shown to be capable of failing | tier 3 — a recorded removal-and-restore, performed and recorded in this session | Ring 5 parity record |
| The shared suite runs on every published artifact, results recorded separately | Requirement: Behaviour is shown to agree on every artifact | tier 3 — five named runs; a row not run is reported as not run | cross-backend parity suite |
| The carrier composes identically on every artifact | Property: The carrier composes the same way on every artifact | tier 3 — property test against an independently computed model, run per row | `carrier-composes-the-same-way` |
| Genuine differences are written down and asserted per artifact | Scenario: A genuine difference is written down | tier 3 — the declared-divergence tables in this and the other specs, each row asserted | cross-backend parity suite |

## Implementation Anchors

The one place in this spec where code identifiers appear.

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `CIO` / `CStream` | opaque types from `kyo.compat` | supplied per row | Resolve to a different concrete effect per artifact; all operations are `inline def`, so no wrapper objects survive compilation |
| `Client[F]` | shared trait | `typesafe4s-client/shared` | Written once against the carrier |
| `LoweredClient[F]` | shared abstract class | `typesafe4s-client/shared` | Requires only `lower` and `lift`; every other operation is `final` |
| Facades | per-row sources | `typesafe4s-client/{zio,ce,ox,kyo,pekko}` | Type alias plus constructors plus stream adapters |
| ZIO typed channel | facade type alias | `typesafe4s-client/zio` | `refineToOrDie` narrows to `TypesafeException` |
| `ApiSurface[F]` | compile-time completeness check | `typesafe4s-client/shared` | Every facade `object TypesafeClient extends ApiSurface[F]`; a forgotten operation stays abstract and fails the row's compile naming it |
| Vendored Cats Effect binding | local module | `typesafe4s-compat-ce` | Kyo removed it upstream at RC6; guarded by `sbt conformanceCe` (346 tests) |
| Parity suite | shared suite over the carrier | `integration-tests/shared/src/test/scala/` | `sbt parityAll`; the baseline row is `Test/compile` only |
