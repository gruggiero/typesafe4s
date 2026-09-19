# Spec: [Capability Name]

<!-- This is a DELTA spec. Use ## ADDED Requirements for new content.
     Use ## MODIFIED Requirements to change existing requirements.
     Use ## REMOVED Requirements to delete requirements.
     Use ## RENAMED Requirements to rename requirement headers.

     Each spec is implemented and verified INDEPENDENTLY through the full
     ring pipeline. Keep specs self-contained — one capability per spec. -->

## Concepts Used (behavioral)

<!-- If the project has a concept registry (openspec/concepts/), cite the
     behavioral concepts this spec touches as Concept or Concept/action,
     with a link to the concept file and one line on the role here.
     Cross-concept requirements should lean on the registry's named
     synchronizations rather than restating imperative recipes.
     If the project has no registry, delete this section. -->

| Concept | Role here | File |
|---------|-----------|------|
| <!-- e.g. Anagrafica --> | <!-- e.g. state gains field X; record payload extended --> | <!-- link --> |

<!-- If this spec alters a concept's actions, state, or synchronizations,
     updating the corresponding openspec/concepts/*.md file is PART OF
     implementing this spec. Say so explicitly here. -->

## Concepts Introduced (new)

<!-- New types, traits, enums, or Smithy operations this spec creates.
     These are COMMITMENTS — the implementation must create exactly these.
     After implementation, these will be added to concept-inventory.md. -->

| Concept | Kind | Description |
|---------|------|-------------|
| <!-- e.g. TransferResult --> | <!-- e.g. case class --> | <!-- e.g. Holds from/to balances after transfer --> |

## ADDED Requirements

<!-- ALTITUDE RULE: requirements and scenarios use behavioral vocabulary only
     — Concept/action references, the project's user-facing surface language
     (DSL paths, API fields), new domain terms, concrete test vectors.
     Module names, error class names, and build commands belong in
     "## Implementation Anchors" at the bottom, never in a Given/When/Then.

     NAME THE ACTOR: when a requirement's obligation is an action of a
     concept cited above, name it Concept/action in the SHALL statement
     ("ScoringProdotti/score SHALL report …"). Reserve "the system" for
     behaviour no single concept owns — a concept cited in the table but
     named nowhere else decorates the spec instead of binding it
     (spec-lint W8). -->

### Requirement: [Requirement Name]

The system SHALL [normative statement of the obligation — one sentence, using
SHALL or MUST. REQUIRED: `openspec validate --strict` rejects a requirement
without it, and scanner/spec-lint.sh fails it as F1. The Given/When/Then
clauses below refine this statement into testable form; they do not replace
it].

**Given** [precondition — initial state or context]
**When** [trigger — action or event]
**Then** [outcome — observable result or state change]

**Rationale**: <!-- Why this requirement exists -->

#### Scenario: [Happy path]

**Given** [specific setup]
**When** [specific action]
**Then** [specific assertion]

#### Scenario: [Error path]

**Given** [specific setup leading to failure]
**When** [specific action]
**Then** [specific error result]

#### Scenario: [Edge case]

**Given** [boundary condition setup]
**When** [action at the boundary]
**Then** [expected boundary behavior]

<!-- Add more requirements and scenarios as needed.
     Each requirement should have at least one happy path
     and one error/edge case scenario.

     ADVERSARIAL RULE: every requirement containing "only", "never", or
     "must not" needs at least one scenario whose INPUT the requirement
     forbids (e.g. for "only registered paths resolve": a scenario feeding
     an unregistered-but-plausible path and asserting it fails). Positive
     examples alone do not pin a negative requirement.

     MUST-CONFIRM RULE: any classification table, code mapping, or value
     domain whose authoritative source is outside the repo must be marked
     **MUST-CONFIRM — do not invent** with a pointer to where the real data
     lives. The apply phase stops and asks; synthesizing plausible values is
     a schema violation. -->

## Properties (Ring 2)

<!-- ScalaCheck properties that the implementation MUST satisfy.
     Write as English invariants AND forAll pseudocode.
     These become the TEST ORACLE, written BEFORE the implementation exists
     (apply Step 2) — they are the SPEC, not the tests. The implementation
     must satisfy them, not the other way around. Generators are PART of the
     approved oracle: narrowing one after approval (adding a filter, tightening
     a Range, dropping an edge case) is oracle tampering and needs re-approval.

     EVERY property declares a **Generator strategy** line (spec-lint F3):
     - which Gen it uses (existing from openspec/concept-inventory.md, or new)
     - constructive (PREFERRED) or filtered — heavy filtering discards inputs
       and silently weakens coverage
     - which edge cases the generator actually reaches
     - classify/collect labels making case coverage visible

     THIS PROJECT'S PROPERTY LIBRARY HAS NO COVERAGE ASSERTION. ScalaCheck's
     classify/collect are informational — they cannot fail a build. A
     conditioned property whose antecedent is rarely generated therefore
     passes VACUOUSLY with nobody noticing. Say how the interesting cases are
     reached by construction. -->

### Property: [Invariant Name]

**Invariant**: [English description of what must always be true]

**Generator strategy**: [Gen name — constructive/filtered — edge cases covered — classify labels]

```
forAll { (param1: Type1, param2: Type2) =>
  [predicate that must hold for all values]
}
```

### Property: [Another Invariant]

**Invariant**: [English description]

**Generator strategy**: [...]

```
forAll { (param: Type) =>
  [predicate]
}
```

<!-- Common property patterns for financial code:
     - Conservation: total money is preserved across operations
     - Monotonicity: balance increases on deposit, decreases on withdraw
     - Roundtrip: decode(encode(x)) == Right(x) for all serialization
     - Idempotence: repeated reads return same result
     - Error totality: every error variant is reachable by some input
     - Commutativity: order-independent operations produce same result -->

## Requirement ↔ Test Cross-Reference

<!-- Maintain one row per Requirement/Scenario in declaration order. The Ring 5
     column records the TraceGate contract or temporal property that covers the
     row. Use `— (redundant, Ring 0)` when an Iron-refined parsed-value
     constraint deliberately has no trace-time contract. -->

| Requirement | Test Name (Ring 2) | Test Name (Ring 3) | Ring 5 |
|-------------|--------------------|--------------------|--------|
| Requirement: [name] | `[test name]` | `—` | `[contract/property]` |
| Scenario: [name] | `[test name]` | `—` | `— (redundant, Ring 0)` |

## Temporal Properties (Ring 5)

<!-- Past-time temporal properties for trace-based verification.
     Only include if the proposal's verification strategy checks Ring 5.
     These translate into SpanContract / TemporalProperty entries in
     Ring5Contracts.scala and a TraceGate suite during the apply phase.

     EARS patterns (past-time — checkable in CI and production):
     - "When <trigger>, the system shall have already <response>"
     - "While <condition>, the system shall <behavior>"
     - "After <event>, until <termination>, the system shall <constraint>"

     Direction: past-time properties are checkable over a complete trace
     in CI (unsampled, ordered) and in production. Future-time
     obligations ("the system shall <response>" after a trigger) are
     CI-only — the trace must be closed before the obligation can be
     evaluated. Prefer past-time formulations.

     Delete this section if Ring 5 does not apply. -->

### Temporal: [Property Name]

**EARS**: "When [trigger], the system shall have already [response]"

**Trigger event**: [the span/event name that opens the obligation — must match
an EventPattern the TraceGate can observe]
**Response event**: [the span/event that must ALREADY have occurred — the
past-time evidence the property requires]

**Redundancy check**: This property is NOT dischargeable by an Iron
constraint on a parsed value. If it is, delete it — Ring 0 already
covers it. Ring 5 earns its keep only where refined types cannot reach
(external boundaries, runtime-interpreted rules, cross-module ordering).

**Property sketch**:
```
TemporalProperty(
  name = "[Property Name]",
  scope = Scope.WithinTrace,        // or WithinSession(key) / WithinWindow(key, duration)
  trigger = EventPattern.Named("[TriggerEvent]"),
  requires = PastCondition.Previously(EventPattern.Named("[ExpectedResponse]"))
)
```

## Compile-Negative Obligations

<!-- When this spec says something must NOT be constructible, list it here.
     Each row becomes an assertDoesNotCompile test in the test oracle
     (apply Step 2), and a top-tier row in Proof Obligations below.

     This is the strongest enforcement the workflow has: a state the type
     system cannot express needs no test to defend it. Reach for this before
     reaching for a runtime check.

     Delete this section if nothing is type-level forbidden. -->

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| <!-- e.g. Expr.Count(c, b, body, CmpOp.IN, n) --> | <!-- IN is not a count-threshold op --> | <!-- assertDoesNotCompile("...") --> |

## Type-Widening Impact

<!-- REQUIRED when this spec aliases, widens, or changes the variant set of a
     PUBLIC type. Aliasing a public type to a richer enum silently widens
     every downstream catch-all match WITH NO FILE EDIT — nothing in the diff
     shows it, and the compiler stays quiet because `case _` still matches.

     List every public type whose effective variant set grows, and how
     existing pattern matches on it must behave. Apply Step 0 runs
     scanner/impact-scan.sh over these and records the sites it finds; each
     site is made exhaustive, made to reject the new variants explicitly, or
     justified with a rationale recorded in Proof Obligations.

     Delete this section if no public type's variant set changes. -->

| Public Type | Variants Added | Required Behaviour of Existing Matches |
|-------------|----------------|----------------------------------------|
| <!-- e.g. CmpOp --> | <!-- IN, BETWEEN --> | <!-- exhaustive; catch-all arms rejected --> |

## Formal Contracts (Ring 4)

<!-- Stainless require/ensuring contracts for pure functions.
     Only include if the proposal's verification strategy checks Ring 4.
     These annotate the implementation during the apply phase.

     Delete this section if Ring 4 does not apply. -->

### Contract: [Function Name]

**Precondition** (`require`): [what must be true before the function runs]
**Postcondition** (`ensuring`): [what must be true after the function returns]

```scala
def functionName(param: Type): ReturnType = {
  require(/* precondition */)
  // implementation
}.ensuring(result => /* postcondition */)
```

## Proof Obligations

<!-- MANDATORY. Map EVERY requirement, scenario, invariant and introduced type
     constraint above to its enforcement mechanism. No spec enters
     implementation while any obligation lacks a declared mechanism.

     SOURCE FORMAT (mandated — scanner/spec-lint.sh checks F6/F7/F8
     mechanically). The Source cell must NAME what the obligation comes from:

       Requirement: <exact requirement title>   PREFERRED — survives reorder
       Requirement N  /  RN                     allowed, but POSITIONAL: a
                                                later reorder silently
                                                re-points it (W4)
       Property: <name>          Scenario: <heading>
       Invariant: <name>         Compile-Negative: <what>
       Temporal: <name>          MUST-CONFIRM: <what>

     Combine with " + ". A bare "Requirement" naming no identifier FAILS (F6).
     A typed reference must name a heading that EXISTS in this spec — a
     mistyped "Property: recall-orderng" binds to nothing (F8).

     COMPLETENESS IS REACHABILITY, NOT ROW COUNT (F7): every requirement must
     be NAMED by at least one obligation. A table with enough rows that
     collectively miss a requirement still fails.

     ARTIFACT: name where the mechanism lives. A code-shaped name (test suite,
     file path) is a COMMITMENT now and a CHECKED FACT after implementation —
     apply Step 11 runs `spec-lint.sh --artifacts` (F9), and a suite named
     here but never written fails. If the real discharge is a manual run, say
     so in Enforcement and name something that exists. Do not leave a
     plausible fiction in the ledger.

     STRENGTH RULE: if the normative statement claims a state is IMPOSSIBLE
     ("cannot be constructed", "unrepresentable", "never"), enforce it at
     tier 1-2. If no type can express it, write "tier-justified: <why not>"
     in Enforcement, or spec-lint W5 flags it.

     MECHANISMS, STRONGEST FIRST:
       1. type system — unrepresentable (cite the type + compile-negative)
       2. smart constructor (cite constructor + rejection property)
       3. property test / scenario test (cite the planned test)
       4. static rule — Scalafix/WartRemover (cite the rule; check the
          capability profile that the rule is ACTIVE, not merely described)
       5. formal contract (Ring 4) — only if the profile says Ring 4 exists
       6. runtime monitor (Ring 5)
       7. adversarial review (Ring 8)
       8. manual review — allowed, but must be explicit -->

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| <!-- e.g. CmpOp.IN never maps to OrderOp --> | Requirement: [Requirement Name] | <!-- type split + compile-negative --> | <!-- EvalSpec, TypeContract --> |
| <!-- e.g. Missing attribute gives Tri.U --> | <!-- Requirement: Missing values propagate + Scenario: absent attribute --> | <!-- property test --> | <!-- EvalSpec --> |
| <!-- e.g. Invalid ID unrepresentable --> | <!-- Invariant: AccountId is well-formed --> | <!-- opaque type + constructor property --> | <!-- domain spec --> |

## Implementation Anchors

<!-- The ONE place in this spec where code identifiers may appear.
     Existing types/modules this spec touches (formerly the type-level
     "Concepts Used" table), the modules each requirement lands in, and any
     build/codegen steps. The apply phase reads this for zero-hop precision;
     for behavior owned by a registry concept, prefer linking the concept
     file's Implementation map over repeating symbols here. -->

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| <!-- e.g. AccountService --> | <!-- trait --> | <!-- package/file --> | <!-- e.g. gains method X --> |
| <!-- e.g. sbt someCodegen/run --> | <!-- build step --> | <!-- module --> | <!-- when to run --> |
