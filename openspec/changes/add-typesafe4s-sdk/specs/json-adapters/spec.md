# Spec: JSON Adapters

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Evaluation | Evaluation/ask — the state an adapter-encoded value feeds | [evaluation.md](../../../../concepts/evaluation.md) |

No new behavioural concept is introduced: the adapters produce values of the existing Entry shape and
feed them to an existing action. They are catalogued here because the registry demands a citation, and
because the concept check (F10) forbids a spec with requirements and no `Concepts Used` section.

## Concepts Introduced (new)

None. The four adapter modules introduce no behavioural concept — they are thin conversions into an
existing shape. The modules themselves are recorded in Implementation Anchors and the type inventory,
not the behavioural registry.

## ADDED Requirements

### Requirement: Each documented JSON library has an adapter

The system SHALL ship one adapter per documented JSON library — circe, zio-json, jsoniter-scala and
upickle — each turning a value of that library's own JSON type into the shape Evaluation/ask accepts
for state, so that a caller of that library adds no second JSON library to their path.

**Given** a caller already uses one of the four documented JSON libraries
**When** they add that library's adapter
**Then** a value of that library's JSON type may serve as evaluation state
**And** no other JSON library is drawn onto their path

**Rationale**: The core chose no JSON library so that no caller pays for a choice they did not make
(wire-codec spec). Without an adapter, a caller holding a circe value would hand-convert it — exactly
the tax the neutral core exists to remove. Four adapters, not fewer, is the recorded decision (design
open question 2 resolved: all proposed adapters ship at 0.1).

#### Scenario: A circe value serves as state

**Given** a caller holding a value of circe's JSON type
**When** they evaluate with it as state
**Then** the state the service receives carries that value's members

#### Scenario: A zio-json value serves as state

**Given** a caller holding a value of zio-json's JSON type
**When** they evaluate with it as state
**Then** the state the service receives carries that value's members

#### Scenario: A jsoniter-scala value serves as state

**Given** a caller holding a value of jsoniter-scala's JSON type
**When** they evaluate with it as state
**Then** the state the service receives carries that value's members

#### Scenario: An upickle value serves as state

**Given** a caller holding a value of upickle's JSON type
**When** they evaluate with it as state
**Then** the state the service receives carries that value's members

#### Scenario: Adapters do not drag each other in

**Given** a caller has added exactly one adapter
**When** the resulting dependency set is read
**Then** only that one JSON library appears
**And** the core itself still names no third-party runtime dependency

### Requirement: Conversion preserves the value

The system SHALL convert a library value into the accepted shape without altering it: member order,
nesting and text survive the crossing unchanged.

**Given** a library value whose every member is a shape the API accepts for state
**When** it is converted
**Then** the resulting value carries the same members in the same order
**And** the same text, structure and emptiness

**Rationale**: A silent rewrite — reordering members, collapsing empty objects, re-rendering text —
turns a faithful adapter into a subtle defect. The caller chose their library; the adapter's only job
is to cross the boundary unchanged.

#### Scenario: Member order survives

**Given** a library object whose members were written in a stated order
**When** it is converted and rendered into a request
**Then** the members appear in that same order

#### Scenario: Nested structure survives

**Given** a library value with nested objects and arrays
**When** it is converted
**Then** the nesting is preserved, level for level

#### Scenario: Nothing stays nothing

**Given** a library value denoting absence (the library's null)
**When** it is converted
**Then** the result is the nothing Entry

### Requirement: A value that cannot be carried is refused where it stops fitting

The system SHALL refuse to convert a library value holding a shape the API cannot carry — a number or
a boolean, at any depth — and the refusal SHALL name the place at which the value stopped fitting.

**Given** a library value holding a number or a boolean somewhere in its tree
**When** conversion is attempted
**Then** it is refused
**And** the refusal names the position of a member that cannot be carried

**Rationale**: The API accepts text, object, array and nothing for state; numbers and booleans are
unrepresentable in the Entry shape (wire-codec spec). Silently stringifying a number would send the
service a different document than the caller wrote. A refusal that names the position lets the caller
see exactly what their library produced that the API cannot take.

#### Scenario: A top-level number is refused

**Given** a library value that is a bare number
**When** conversion is attempted
**Then** it is refused, naming the root position

#### Scenario: A nested boolean is refused

**Given** a library object whose third member holds a boolean
**When** conversion is attempted
**Then** it is refused, naming that member's position

#### Scenario: A refused value never reaches the wire

**Given** a library value that conversion refuses
**When** it is offered as evaluation state
**Then** no request is sent
**And** the caller is told where the value stopped fitting

## Properties (Ring 2)

### Property: Conversion of a carryable value is faithful, order included

**Invariant**: For every value whose members are all carryable shapes, conversion yields a value that
renders identically — same members, same order, same nesting, same text.

**Generator strategy**: `genEntryJson` — constructive, shared with the wire-codec spec's JSON
generator restricted to carryable shapes (text, object, array, nothing) so the draw always admits
conversion; member order is recorded at generation so order preservation is asserted, not assumed.
`classify` labels: top-level shape, nesting depth.

```
forAll { (value: EntryShapedJson) =>
  render(convert(value)) == render(value)
}
```

### Property: A refusal names a real violation

**Invariant**: For every value holding at least one non-carryable member, conversion refuses, and the
named position locates a member that is genuinely a number or a boolean.

**Generator strategy**: `genViolatingJson` — constructive. Draws an entry-shaped value, then plants a
number or boolean at a randomly chosen position, so a violation exists by construction on every run.
`classify` labels: violation depth, violation kind.

```
forAll { (value: JsonWithViolation) =>
  convert(value) match {
    case Left(refusal) => memberAt(value, refusal.path).isNonCarryable
    case Right(_)      => false
  }
}
```

## Requirement ↔ Test Cross-Reference

| Requirement | Test Name (Ring 2) | Test Name (Ring 3) | Ring 5 (parity) |
|-------------|--------------------|--------------------|-----------------|
| Requirement: Each documented JSON library has an adapter | `adapter-dependency-isolation` × 4 | `—` | `— (adapters compile once)` |
| Requirement: Conversion preserves the value | `conversion-is-faithful-order-included` | `—` | `— (adapters compile once)` |
| Requirement: A value that cannot be carried is refused where it stops fitting | `a-refusal-names-a-real-violation` | `—` | `— (adapters compile once)` |

## Cross-Backend Parity (Ring 5)

**Applies: NO.** The adapters are pure conversions in standalone modules — they touch no transport,
no effect system and no facade, and each compiles once rather than per backend row. A caller on any
row adds the same adapter artifact.

Recorded rather than omitted, because applicability is a fact read from the changed-file list.

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A library JSON value used as state without its adapter | The conversion must be an explicit caller choice; silent availability would hide which library is doing the work | `compileErrors` — no given resolves without the adapter's import |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| All four adapters exist | Requirement: Each documented JSON library has an adapter | tier 4 — one module per library in the build; absence fails the build | adapter modules in the build |
| An adapter draws in only its own library | Scenario: Adapters do not drag each other in | tier 4 — per-adapter dependency assertion; **tier-justified**: "this module has exactly one third-party dependency" is a build fact no type can express | adapter dependency assertions |
| The core still names no third-party runtime dependency | Scenario: Adapters do not drag each other in | tier 4 — the existing core dependency assertion must keep passing with the adapters in the build | core dependency assertion in the build |
| Conversion preserves members, order and structure | Requirement: Conversion preserves the value + Property: Conversion of a carryable value is faithful, order included | tier 3 — property test per adapter, one generator shared across the four | `conversion-is-faithful-order-included` |
| Absence converts to the nothing Entry | Scenario: Nothing stays nothing | tier 3 — scenario or property case per adapter | adapter conversion suite |
| A non-carryable value is refused naming its position | Requirement: A value that cannot be carried is refused where it stops fitting + Property: A refusal names a real violation | tier 3 — property test per adapter asserting the named position is a real violation | `a-refusal-names-a-real-violation` |
| A refused value reaches no wire | Scenario: A refused value never reaches the wire | tier 2 — refusal happens before request construction; a control case renders the state into the request body while the refused case yields no JsonEntry, so no request is constructible. Adapter modules reach `SystemOne.renderRequest` — the wire boundary — not `Transport` | adapter conversion suite |
| The conversion mechanism for refusal is chosen | Requirement: A value that cannot be carried is refused where it stops fitting | RESOLVED at design review — **validated wrapper + total given**: each adapter defines an opaque wrapper whose only constructor is a checked conversion (`Either` naming the violating path), and a `StateEncoder` given whose totality is honest because the wrapper only holds already-validated values — the same pattern `Entry` itself uses (checked `fromJson`, total consumers). Silent coercion and throwing givens were rejected | adapter type contracts |

## Implementation Anchors

The one place in this spec where code identifiers appear.

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `typesafe4s-circe` | module | `typesafe4s-json-circe/` | circe's JSON type → Entry; depends on circe-core only |
| `typesafe4s-zio-json` | module | `typesafe4s-json-zio/` | zio-json's JSON type → Entry; depends on zio-json only |
| `typesafe4s-jsoniter` | module | `typesafe4s-json-jsoniter/` | jsoniter-scala's JSON type → Entry; depends on jsoniter-scala-core only |
| `typesafe4s-upickle` | module | `typesafe4s-json-upickle/` | upickle's JSON type (`ujson.Value`) → Entry; depends on ujson only |
| Adapter wrapper | opaque type, one per adapter | each adapter's package | e.g. `CirceEntry`: `of(libraryValue): Either[String, CirceEntry]` is the only constructor; a total `StateEncoder` given encodes the stored Entry |
| `StateEncoder` | typeclass | `typesafe4s-core` | Total `encode: A => Entry` — the wrapper makes the adapter's given honest |
| `Entry.fromJson` | checked conversion | `typesafe4s-core` | Existing dotted-path refusal; adapters delegate to it after crossing to core `Json` |
| Dependency assertions | build tasks | `build.sbt` | Per-adapter: exactly one third-party library; core assertion unchanged |
| Conversion suites | munit `ScalaCheckSuite` | each adapter's test sources | Shared generator restricted to entry-shaped values, plus the planted-violation generator |
