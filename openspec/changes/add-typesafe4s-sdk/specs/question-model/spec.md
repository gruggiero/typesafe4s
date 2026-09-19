# Spec: Question Model

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Noul | `Noul/ask` — this spec gives the yes/no probability a shape callers can read without interpreting prose | [noul.md](../../../../concepts/noul.md) |
| Choice | `Choice/ask` — this spec carries the closed option set into the answer, so a caller can branch over it exhaustively | [choice.md](../../../../concepts/choice.md) |
| Score | `Score/ask` — this spec carries the rubric's level count into the answer and keeps position and spread together | [score.md](../../../../concepts/score.md) |
| Confidence | `Confidence/derive` — reported on Choice and Score answers, and deliberately absent from Noul | [confidence.md](../../../../concepts/confidence.md) |
| Evaluation | `Evaluation/ask` — this spec defines how a question set is named and how its answers are addressed | [evaluation.md](../../../../concepts/evaluation.md) |

This spec does not alter any concept's actions, state or synchronizations. It gives them their first
implementation, so each concept's Implementation map is filled in as part of applying this spec.

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| Question | sealed type indexed by its answer | A question carries the type of the answer it yields, so the two cannot disagree |
| NoulAnswer | product type | A probability in [0, 1]. Has **no** certainty member. |
| ChoiceAnswer | product type indexed by the option set | Selected option, spread over all options, certainty |
| ScoreAnswer | product type indexed by the level count | Position, legend, spread, certainty |
| AnswerSet | keyed collection | Answers addressed by the names their questions were given |

## ADDED Requirements

### Requirement: An answer's shape follows from the question asked

Noul/ask, Choice/ask and Score/ask SHALL each yield an answer whose available members follow from the
question that produced it, so that a question and its answer cannot disagree about what was asked.
Confidence/derive reports a certainty on Choice and Score answers and MUST NOT appear on a Noul answer.

**Given** a caller has asked a question of one of the three kinds
**When** the answer is read
**Then** only the members that kind of answer actually carries are available

**Rationale**: The API returns three different answer shapes. Reading a member the API never sent is a
defect that should be impossible to write, not a run-time failure.

#### Scenario: A Noul answer offers a probability and nothing else

**Given** a caller has asked `Noul/ask`
**When** the answer is read
**Then** it offers the probability that the claim holds
**And** that probability lies in [0, 1]

#### Scenario: A Noul answer offers no certainty

**Given** a caller has asked `Noul/ask`
**When** the caller attempts to read a certainty from the answer
**Then** the attempt is refused before the program runs
**And** the refusal is not deferred to a failed request

#### Scenario: A Choice answer carries its option set

**Given** a caller has asked `Choice/ask` over a closed set of options
**When** the answer is read
**Then** the selected option is one of exactly those options
**And** the spread and the certainty are both available

#### Scenario: A Score answer may sit between levels

**Given** a caller has asked `Score/ask` over an ordered rubric
**When** the answer is read
**Then** the position may fall between two level numbers
**And** the legend, the spread and the certainty are all available

### Requirement: A question set written in source yields a matching answer set

Evaluation/ask SHALL return, for a question set written literally in source, an answer set with the
same names and the same number of entries, each entry shaped by its own question.

**Given** a caller writes a question set literally, naming each question
**When** the answers come back
**Then** each answer is addressed by the name its question was given
**And** each answer has the shape that question's kind produces

**Rationale**: Addressing answers by string key makes a typo a run-time failure and hides which answer
shape to expect. Deriving both from the names already written removes a whole class of defect and
means a question key is never written twice.

#### Scenario: Names and shapes are both carried through

**Given** a caller asks one Noul named `refundRequested` and one Choice named `department`
**When** the answers come back
**Then** the answer named `refundRequested` offers a probability
**And** the answer named `department` offers a selected option drawn from that Choice's options

#### Scenario: A name that was never asked is refused

**Given** a caller asks a question set that does not include `escalation`
**When** the caller addresses an answer named `escalation`
**Then** the attempt is refused before the program runs

#### Scenario: Request keys come from the names already written

**Given** a caller has written a question set literally
**When** the request is formed
**Then** each question is keyed by the name the caller gave it
**And** the caller has not written that name a second time

#### Scenario: A selected option can be exhausted over

**Given** a caller has asked a Choice over a closed set of options
**When** the caller distinguishes every option in that set
**Then** no further case is required to cover the answer
**And** omitting one of the options is reported before the program runs

### Requirement: Choice options may be taken from a domain enumeration

Choice/ask SHALL accept a domain enumeration as the source of its options, sending that enumeration's
case labels as the option keys and returning the selected option as a member of that enumeration.

**Given** a caller has an enumeration whose cases are the options
**When** that enumeration is used to form a Choice
**Then** its case labels are sent as the option keys
**And** the selected option comes back as a member of that enumeration

**Rationale**: The API guarantees the answer is one of the supplied options. When the options are a
domain enumeration, returning text throws that guarantee away and forces every caller to re-parse it.

#### Scenario: Options taken from an enumeration

**Given** an enumeration with the cases Billing, Technical and Sales
**When** a Choice is formed from it
**Then** those three labels are sent as the option keys
**And** the selected option is one of those three enumeration members

#### Scenario: An option the enumeration does not contain

**Given** a Choice whose options came from an enumeration
**When** the response names an option matching none of its cases
**Then** the call fails, naming the entry that did not match
**And** no partially decoded answer set is returned

### Requirement: Documented limits are refused before a request is made

Score/ask, Choice/ask and Evaluation/ask SHALL each refuse a question or question set whose shape
breaks a documented limit, before the program runs, whenever the breaking values are written in source.

**Given** a caller writes a question whose shape breaks a documented limit
**When** the values that break it are written in source
**Then** the program is refused before it runs
**And** no request is made

**Rationale**: These limits are fixed and published. A question that breaks one can never succeed, so
spending a round trip to be told is waste — and an unrepresentable state needs no test to defend it.

#### Scenario: A rubric with too few levels

**Given** a caller writes a Score with one level
**When** the program is built
**Then** it is refused, stating that a Score needs at least two levels

#### Scenario: A rubric with too many levels

**Given** a caller writes a Score with eleven levels
**When** the program is built
**Then** it is refused, stating the ten-level maximum

#### Scenario: More options than a Choice accepts

**Given** a caller writes a Choice with 256 options
**When** the program is built
**Then** it is refused, stating the 255-option maximum

#### Scenario: A question set with nothing in it

**Given** a caller writes a question set containing no questions
**When** the program is built
**Then** it is refused, because an evaluation must ask at least one question

#### Scenario: A limit broken only at run time still fails safely

**Given** a caller builds a Choice at run time with more than 255 options
**When** the question set is submitted
**Then** the call fails, naming the question that exceeds the limit
**And** the failure is reported before any request is made

### Requirement: Question sets built at run time are equally supported

Evaluation/ask SHALL accept a question set assembled at run time, addressing its answers by the same
names, and SHALL decode both that set and a set written in source through one decoder.

**Given** a caller assembles a question set at run time
**When** the answers come back
**Then** each answer is addressed by the name its question was given
**And** a name that was not asked yields a failure value rather than raising

**Rationale**: Several documented uses build one question per retrieved item, so the set is not
writable in source. Two decoders would be two places for the wire contract to drift.

#### Scenario: One question per retrieved item

**Given** a caller builds one question for each of thirty retrieved items
**When** the set is submitted
**Then** every one of the thirty answers is addressed by its own name

#### Scenario: Reading an answer that was asked

**Given** a question set that asked a Noul named `refundRequested`
**When** the caller reads that name expecting a Noul answer
**Then** the probability is returned as a success value

#### Scenario: Reading a name that was never asked

**Given** a question set that did not ask `escalation`
**When** the caller reads that name
**Then** a failure value naming `escalation` is returned
**And** nothing is raised

#### Scenario: Reading an answer as the wrong kind

**Given** a question set that asked `department` as a Choice
**When** the caller reads `department` expecting a Noul answer
**Then** a failure value is returned
**And** nothing is raised

## Properties (Ring 2)

### Property: Every asked question is answered exactly once

**Invariant**: For any question set, the names in the answer set equal the names in the question set —
none dropped, none invented, none duplicated.

**Generator strategy**: `genQuestionSet` — constructive. Builds a map of 1–20 questions by drawing a
kind uniformly and generating a well-formed question of that kind, then draws distinct names from a
small alphabet so collisions are structurally impossible. Edge cases reached by construction: the
single-question set (size 1 is in range), all-same-kind sets, mixed-kind sets. `classify` labels:
question count bucket (1, 2–5, 6–20) and kind mix (uniform / mixed).

```
forAll { (questions: QuestionSet) =>
  val answers = decode(respondTo(questions))
  answers.names == questions.names
}
```

### Property: A selected option is always one the caller supplied

**Invariant**: A Choice answer's selected option is a member of that Choice's option set, and its
spread is defined over exactly that set.

**Generator strategy**: `genChoice` — constructive. Draws an option set of 1–255 distinct labels, then
draws a response selecting an index into that set, so an out-of-set answer is unrepresentable by the
generator and the property tests the decoder rather than the model. Edge cases reached by
construction: single-option, the 255-option boundary, labels differing only by case. `classify`
labels: option-count bucket (1, 2–10, 11–254, 255).

```
forAll { (choice: Choice, response: ChoiceResponse) =>
  choice.options.contains(decode(choice, response).selected) &&
    decode(choice, response).spread.keys == choice.options
}
```

### Property: A rubric position lies inside the rubric

**Invariant**: A Score answer's position lies between zero and one less than the level count, and its
spread is defined over exactly those level numbers.

**Generator strategy**: `genScore` — constructive. Draws a level count uniformly from 2 to 10 — the
entire documented range, so both boundaries are always reachable — then draws a spread over exactly
those levels and derives the position as their probability-weighted mean. No filtering. `classify`
labels: level count, and whether the position is integral or fractional.

```
forAll { (score: Score, response: ScoreResponse) =>
  val answer = decode(score, response)
  answer.position >= 0 && answer.position <= score.levelCount - 1 &&
    answer.spread.keys == score.levelNumbers
}
```

### Property: Both call surfaces form the same request

**Invariant**: A question set written in source and the same set assembled at run time produce
byte-identical request bodies.

**Generator strategy**: `genQuestionSet` reused, restricted to sets expressible both ways (fixed
arity), then rendered through each surface. Constructive — no filtering, because the restriction is
applied when generating rather than after. `classify` labels: arity, kind mix.

```
forAll { (questions: QuestionSet) =>
  renderTyped(questions) == renderDynamic(questions)
}
```

## Requirement ↔ Test Cross-Reference

| Requirement | Test Name (Ring 2) | Test Name (Ring 3) | Ring 5 (parity) |
|-------------|--------------------|--------------------|-----------------|
| Requirement: An answer's shape follows from the question asked | `answer-shape-follows-question` | `—` | `— (decoding is pure; core compiles once)` |
| Scenario: A Noul answer offers no certainty | compile-negative | `—` | `— (refused before the program runs)` |
| Requirement: A question set written in source yields a matching answer set | `every-asked-question-is-answered-exactly-once` | `—` | `— (core only)` |
| Requirement: Choice options may be taken from a domain enumeration | `selected-option-is-always-supplied` | `—` | `— (core only)` |
| Requirement: Documented limits are refused before a request is made | compile-negative + `runtime-limit-fails-before-request` | `—` | `— (refused before the program runs)` |
| Requirement: Question sets built at run time are equally supported | `both-surfaces-form-the-same-request` | `—` | `— (core only)` |

## Cross-Backend Parity (Ring 5)

**Applies: NO.** Every requirement here is discharged inside the pure, sans-IO core, which is compiled
once rather than per backend row. Nothing in this spec is observable through more than one backend
artifact, and no requirement touches the transport, the retry loop or a facade.

Recorded rather than omitted, because Ring 5 applicability is a fact read from the changed-file list,
and a reader must be able to see it was read.

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| Reading a certainty from a Noul answer | The API returns none for nouls; the probability *is* the certainty | `compileErrors` on the member access |
| A Score with fewer than two levels | Documented minimum | `compileErrors`, asserting the message names the two-level minimum |
| A Score with more than ten levels | Documented maximum | `compileErrors`, asserting the message names the ten-level maximum |
| A Choice with more than 255 statically known options | Documented maximum | `compileErrors`, asserting the message names the 255-option maximum |
| A question set containing no questions | An evaluation must ask at least one question | `compileErrors` |
| Addressing an answer by a name that was never asked | The answer set's names are exactly the question set's | `compileErrors` |
| Reading a Choice answer as a Noul answer through the typed surface | Answer shape follows the question | `compileErrors` |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| A Noul answer has no certainty member | Requirement: An answer's shape follows from the question asked + Scenario: A Noul answer offers no certainty | tier 1 — the type carries no such member; unrepresentable | question-model compile-negative suite |
| Each answer kind exposes exactly its own members | Requirement: An answer's shape follows from the question asked | tier 1 — answer type indexed by the question type | question-model type contract |
| A probability lies in [0, 1] | Scenario: A Noul answer offers a probability and nothing else | tier 3 — property test over decoded responses; **tier-justified**: the bound is a property of data the server sends, not of a value this code constructs, so no constructor can refuse it | `answer-shape-follows-question` |
| Answer names equal question names | Requirement: A question set written in source yields a matching answer set | tier 1 for the source-written surface (names derived, not restated) + tier 3 for the run-time surface | question-model type contract + `every-asked-question-is-answered-exactly-once` |
| An unasked name cannot be addressed | Requirement: A question set written in source yields a matching answer set + Scenario: A name that was never asked is refused | tier 1 — unrepresentable in the source-written surface | question-model compile-negative suite |
| An unasked name yields a failure value, never a raise | Requirement: Question sets built at run time are equally supported + Scenario: Reading a name that was never asked | tier 2 — lookup returns a failure value by construction | question-model decoder suite |
| Selected option is drawn from the supplied set | Requirement: Choice options may be taken from a domain enumeration + Property: A selected option is always one the caller supplied | tier 2 — decoder maps through the supplied set and fails otherwise | `selected-option-is-always-supplied` |
| An unmatched option fails, naming the entry | Scenario: An option the enumeration does not contain | tier 2 — total decode with a named failure | question-model decoder suite |
| Score levels number 2..10 when written in source | Requirement: Documented limits are refused before a request is made | tier 1 — refused before the program runs | question-model compile-negative suite |
| Choice options number at most 255 when written in source | Requirement: Documented limits are refused before a request is made | tier 1 — refused before the program runs | question-model compile-negative suite |
| A question set is never empty when written in source | Requirement: Documented limits are refused before a request is made | tier 1 — refused before the program runs | question-model compile-negative suite |
| A limit broken at run time fails before any request | Scenario: A limit broken only at run time still fails safely | tier 2 — smart constructor rejects, no transport call | question-model decoder suite |
| Rubric position lies inside the rubric | Property: A rubric position lies inside the rubric | tier 3 — property test | `rubric-position-lies-inside-the-rubric` |
| Both surfaces form one request through one decoder | Requirement: Question sets built at run time are equally supported + Property: Both call surfaces form the same request | tier 3 — differential property test | `both-surfaces-form-the-same-request` |

## Implementation Anchors

The one place in this spec where code identifiers appear.

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `Question[A]` | sealed trait, GADT | `typesafe4s-core`, package `typesafe4s` | Indexed by the answer type; `Noul`, `Choice`, `Score` are its cases |
| `AnswerOf` | match type | `typesafe4s-core` | Maps a question type to its answer type |
| `NoulAnswer` / `ChoiceAnswer` / `ScoreAnswer` | product types | `typesafe4s-core` | `NoulAnswer` deliberately has no `confidence` member |
| Named-tuple surface | inline method | `typesafe4s-core` | `NamedTuple.Map` over `AnswerOf`; wire keys via `constValueTuple`. Needs Scala 3.7+; shape verified in `docs/architecture-analysis.md` Appendix A |
| Enumeration-derived Choice | inline method using `Mirror.SumOf` | `typesafe4s-core` | Labels via `constValueTuple`; cases via `summonInline[ValueOf[_]]` |
| Limit enforcement | `inline if` + `compiletime.error` | `typesafe4s-core` | Verified to fire in `docs/architecture-analysis.md` Appendix A |
| Type contract | test sources | `typesafe4s-core/src/test/scala/typesafe4s/typecontract/` | Compile with `sbt core/Test/compile` |
| Compile-negative suite | munit `compileErrors` | `typesafe4s-core/src/test/scala/` | **Not** ScalaTest's `assertDoesNotCompile` — ScalaTest is not on the classpath |
| Property suites | munit `ScalaCheckSuite` | `typesafe4s-core/src/test/scala/` | munit-scalacheck 1.3.1; **no coverage assertion**, so reachability is constructive |
