# Spec: Wire Codec

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Evaluation | Evaluation/ask — this spec defines the bytes that carry a question set out and the answers back | [evaluation.md](../../../../concepts/evaluation.md) |
| Noul | Noul/ask — its request shape and its single answer field | [noul.md](../../../../concepts/noul.md) |
| Choice | Choice/ask — its option map and its three answer fields | [choice.md](../../../../concepts/choice.md) |
| Score | Score/ask — its ordered levels and its four answer fields | [score.md](../../../../concepts/score.md) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| Json | recursive sum type | A JSON value. Object members keep the order they were given. |
| Entry | restricted Json | Text, object, array or nothing — the shapes the API accepts for instructions and descriptions |
| StateEncoder | typeclass | Turns a caller's own value into an Entry, so no JSON library is imposed |
| Usage | product type | Token counts, each independently absent |

## ADDED Requirements

### Requirement: The core carries no third-party runtime dependency

The system SHALL parse and render JSON using only what it defines itself, so that Evaluation/ask imposes
no JSON library on a caller.

**Given** a caller depends on the pure core
**When** its published dependency list is read
**Then** it names nothing beyond the language's own standard library

**Rationale**: Choosing a JSON library taxes every caller who already uses a different one, in the same
way that choosing an effect system would. The tax is avoided by choosing neither.

#### Scenario: The published dependency list is empty

**Given** the pure core has been published
**When** its dependency list is read
**Then** no third-party entry appears in it

#### Scenario: A caller brings their own encoder

**Given** a caller already has an encoder for their own type from some JSON library
**When** they add the matching adapter for that library
**Then** their type may be used as evaluation state
**And** no second JSON library is drawn onto their path

#### Scenario: Adapters do not drag each other in

**Given** a caller has added exactly one such adapter
**When** the resulting dependency set is read
**Then** only that one JSON library appears

### Requirement: A rendered value can always be read back

The system SHALL render every JSON value it can hold such that reading the result back yields the value
it started from, preserving the order of object members.

**Given** a JSON value the core can hold
**When** it is rendered and then read back
**Then** the result equals what was rendered
**And** object members are in the order they were given

**Rationale**: Request bodies must be reproducible for recorded comparisons to mean anything. Order that
drifts turns a byte-for-byte fixture into a flaky test.

#### Scenario: A value survives the round trip

**Given** an object holding nested objects, arrays, text, numbers and nothing
**When** it is rendered and read back
**Then** the value that comes back equals the one that went in

#### Scenario: Member order is not rearranged

**Given** an object whose members were given in a stated order
**When** it is rendered
**Then** its members appear in that same order

#### Scenario: Text that needs escaping survives

**Given** text containing quotes, backslashes, newlines and characters outside the ASCII range
**When** it is rendered and read back
**Then** the text that comes back equals the text that went in

### Requirement: A request carries the shape each question kind requires

The system SHALL render Noul/ask, Choice/ask and Score/ask each with its own kind marker and the
criteria shape that kind takes, alongside the state and the model being asked.

**Given** a question set and the state it asks about
**When** the request is rendered
**Then** each question carries its kind marker and its instructions
**And** each question carries criteria in the shape its kind takes

**Rationale**: The three kinds differ on the wire — a map of options, an ordered list of levels, or an
optional pair of meanings. Rendering one kind's shape for another is silently accepted as a bad request.

#### Scenario: A yes/no question on the wire

**Given** a Noul with no meanings supplied
**When** the request is rendered
**Then** it carries the noul kind marker and the instructions
**And** it carries no criteria at all

#### Scenario: A yes/no question with meanings supplied

**Given** a Noul with both meanings supplied
**When** the request is rendered
**Then** its criteria carry those two meanings under their documented names

#### Scenario: A closed-set question on the wire

**Given** a Choice over three options, one of them with no description
**When** the request is rendered
**Then** its criteria map each option name to its description
**And** the option with no description maps to nothing

#### Scenario: A rubric question on the wire

**Given** a Score over four ordered levels
**When** the request is rendered
**Then** its criteria are an ordered list of those four level descriptions
**And** their order is the order the caller declared

#### Scenario: The model is always named

**Given** a caller who named no model for this call
**When** the request is rendered
**Then** it names the model the client was configured with

#### Scenario: Structure is sent as structure

**Given** instructions given as an object rather than text
**When** the request is rendered
**Then** that object is carried as an object
**And** it is not flattened into a string

### Requirement: A response that does not fit is rejected where it broke

The system SHALL read each answer according to the kind marker it carries, and SHALL reject a response
that does not fit, naming the path at which it stopped fitting.

**Given** a response to a question set
**When** it is read
**Then** each answer is read as the kind its marker names
**And** a response that does not fit is rejected, naming where

**Rationale**: A silently mis-read answer produces a plausible wrong value that propagates. Rejecting at
the point of mismatch turns that into one legible failure.

#### Scenario: A mixed answer set is read

**Given** a response carrying one answer of each kind
**When** it is read
**Then** each answer is read as its own kind
**And** the model that answered and the token counts are both available

#### Scenario: Token counts may be missing

**Given** a response that reports neither token count
**When** it is read
**Then** the counts are reported as absent
**And** the call does not fail

#### Scenario: A successful response whose body does not fit

**Given** a response the server reported as successful
**When** its body does not fit the shape agreed with the API
**Then** the call fails, naming the path at which it stopped fitting

#### Scenario: An unrecognised member is passed over

**Given** a response carrying a member this version does not recognise
**When** it is read
**Then** reading succeeds
**And** the unrecognised member changes nothing

#### Scenario: An answer marker that is not recognised

**Given** a response carrying an answer whose kind marker names no known kind
**When** it is read
**Then** the call fails, naming that answer
**And** no partially read answer set is returned

## Properties (Ring 2)

### Property: Rendering then reading is the identity

**Invariant**: For every JSON value the core can hold, reading back what was rendered yields an equal
value, member order included.

**Generator strategy**: `genJson` — constructive, depth-bounded. Draws a shape (nothing, boolean, number,
text, array, object) with depth capped at 4 so recursion terminates without filtering; text is drawn from
an alphabet that deliberately includes quote, backslash, newline, and characters outside the ASCII range,
so escaping is exercised on nearly every run rather than by chance. Objects draw 0–6 distinct members, so
the empty object is reachable. `classify` labels: top-level shape, depth, and whether any text needed
escaping.

```
forAll { (value: Json) =>
  parse(render(value)) == Right(value)
}
```

### Property: Every asked question appears in the request exactly once

**Invariant**: A rendered request carries one question entry per asked name, keyed by that name, and no
others.

**Generator strategy**: `genQuestionSet` — constructive, shared with the question-model spec. Distinct
names drawn from a small alphabet so collisions cannot occur. 1–20 questions, mixed kinds. `classify`
labels: question count bucket, kind mix.

```
forAll { (questions: QuestionSet) =>
  renderedQuestionNames(render(questions)) == questions.names
}
```

### Property: Reading is total over well-formed responses

**Invariant**: For any response built from a question set, reading either yields one answer per asked
name or fails naming a path — it never yields a partial answer set and never raises.

**Generator strategy**: `genResponseFor(questions)` — constructive, built *from* a generated question
set so that kind markers and question names agree by construction; a corrupting step then perturbs one
randomly chosen path (drop a member, change a marker, change a type) on roughly half of runs, so both
the fitting and non-fitting branches are reached by construction rather than by luck. `classify` labels:
perturbed or not, and which perturbation.

```
forAll { (questions: QuestionSet, response: Response) =>
  decode(questions, response) match {
    case Right(answers) => answers.names == questions.names
    case Left(failure)  => failure.path.nonEmpty
  }
}
```

## Requirement ↔ Test Cross-Reference

| Requirement | Test Name (Ring 2) | Test Name (Ring 3) | Ring 5 (parity) |
|-------------|--------------------|--------------------|-----------------|
| Requirement: The core carries no third-party runtime dependency | `core-declares-no-runtime-dependency` | `—` | `— (core compiles once)` |
| Requirement: A rendered value can always be read back | `render-then-parse-is-identity` | `—` | `— (core compiles once)` |
| Requirement: A request carries the shape each question kind requires | `every-asked-question-appears-once` + recorded fixtures | `—` | `— (core compiles once)` |
| Requirement: A response that does not fit is rejected where it broke | `reading-is-total-over-well-formed-responses` | `—` | `— (core compiles once)` |

## Cross-Backend Parity (Ring 5)

**Applies: NO.** Every requirement here lives in the pure, sans-IO core, which is compiled once rather
than per backend row. No requirement touches the transport, the retry loop or a facade.

Recorded rather than omitted, because applicability is a fact read from the changed-file list.

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| An Entry holding a number or a boolean at top level | The API accepts text, object, array or nothing there | `compileErrors` on the smart constructor |
| Rendering a request with no questions | An evaluation must ask at least one question | `compileErrors` |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| The core names no third-party runtime dependency | Requirement: The core carries no third-party runtime dependency | tier 4 — a build assertion over the published dependency list; **tier-justified**: no type can express "this module has no dependencies"; it is a build fact and must be checked as one | core dependency assertion in the build |
| One adapter draws in only its own JSON library | Scenario: Adapters do not drag each other in | tier 4 — per-adapter dependency assertion | adapter dependency assertions |
| Render then read is the identity, order included | Requirement: A rendered value can always be read back + Property: Rendering then reading is the identity | tier 3 — property test | `render-then-parse-is-identity` |
| Escaped text survives the round trip | Scenario: Text that needs escaping survives | tier 3 — property test whose alphabet includes the escaping cases by construction | `render-then-parse-is-identity` |
| Each question kind renders its own criteria shape | Requirement: A request carries the shape each question kind requires | tier 1 for the shape (each kind carries only its own criteria type) + tier 3 for the bytes | wire-codec type contract + recorded request fixtures |
| A question with no criteria omits the member entirely | Scenario: A yes/no question on the wire | tier 3 — recorded fixture comparison | recorded request fixtures |
| Rubric level order is the declared order | Scenario: A rubric question on the wire | tier 2 — levels held in an ordered structure by construction | wire-codec type contract |
| Structured instructions are not flattened | Scenario: Structure is sent as structure | tier 1 — Entry holds structure, so flattening is unrepresentable | wire-codec type contract |
| Every asked question is rendered exactly once | Requirement: A request carries the shape each question kind requires + Property: Every asked question appears in the request exactly once | tier 3 — property test | `every-asked-question-appears-once` |
| A request always names a model | Scenario: The model is always named | tier 1 — model is a required render parameter, so omission is unrepresentable at this layer; the configured-default resolution lands with the client (client-configuration spec) | wire-codec type contract + recorded request fixtures |
| The answering model is available | Scenario: A mixed answer set is read | tier 1 — the response product carries it as a required field | wire-codec decoder suite |
| Answers are read by their kind marker | Requirement: A response that does not fit is rejected where it broke | tier 2 — total dispatch on the marker, unknown markers rejected | wire-codec decoder suite |
| A non-fitting body fails naming a path | Requirement: A response that does not fit is rejected where it broke + Property: Reading is total over well-formed responses | tier 3 — property test asserting the path is non-empty | `reading-is-total-over-well-formed-responses` |
| Absent token counts do not fail the call | Scenario: Token counts may be missing | tier 1 — each count independently optional | wire-codec type contract |
| Unrecognised members change nothing | Scenario: An unrecognised member is passed over | tier 3 — recorded fixture with an added member | wire-codec decoder suite |

## Implementation Anchors

The one place in this spec where code identifiers appear.

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `Json` | enum | `typesafe4s-core`, package `typesafe4s.json` | Objects hold members in a structure that preserves insertion order |
| `Entry` | opaque type over `Json` | `typesafe4s-core` | Smart constructors restrict it to text, object, array, nothing |
| `StateEncoder[A]` | typeclass | `typesafe4s-core` | Given instances for `String`, `Json`, `Map`, `Seq`, `Option`, tuples |
| Adapters | one given each | `typesafe4s-circe`, `typesafe4s-zio-json`, `typesafe4s-jsoniter`, `typesafe4s-upickle` | ~10 lines apiece; each depends on exactly one JSON library |
| Recorded fixtures | test resources | `typesafe4s-core/src/test/resources/` | Taken from the documented request and response examples |
| Property suites | munit `ScalaCheckSuite` | `typesafe4s-core/src/test/scala/` | munit-scalacheck 1.3.1; no coverage assertion, so reachability is constructive |
| Dependency assertion | build task | `build.sbt` | Asserts `core/libraryDependencies` holds only the Scala library plus test-scope entries |
