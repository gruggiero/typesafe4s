# Spec: Question Batching

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| TokenBudget | TokenBudget/fit, TokenBudget/split — this spec is that concept's whole behaviour | [token-budget.md](../../../../concepts/token-budget.md) |
| Evaluation | Evaluation/ask — asked once per batch, against the same state | [evaluation.md](../../../../concepts/evaluation.md) |
| Retry | Retry/schedule — a batch that fails after its attempts are spent fails the whole stream | [retry.md](../../../../concepts/retry.md) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| TokenEstimate | measure | A conservative, deliberately approximate size for a state and question set |
| Batch | ordered partition | A subset of the questions that fits alongside the state |

## ADDED Requirements

### Requirement: Questions about one state travel together

Evaluation/ask SHALL carry every question about one state in a single exchange whenever they fit, because
questions are answered independently and adding them costs little.

**Given** several questions about one state
**When** they fit within the budget
**Then** they travel in one exchange
**And** every answer comes back from that exchange

**Rationale**: The service answers questions in parallel and charges mainly for the questions' own words.
Splitting what would fit means paying for the state repeatedly and waiting serially for no gain.

#### Scenario: Several questions, one exchange

**Given** a caller asks several questions about one state
**When** they fit within the budget
**Then** exactly one exchange is made

#### Scenario: Answers do not influence one another

**Given** several questions asked together
**When** the answers come back
**Then** no answer was used as context for another
**And** each is addressed by its own name

### Requirement: The size of an exchange is estimated before it is sent

TokenBudget/fit SHALL estimate the size of a state and question set together, report that estimate to
callers, and describe it as approximate.

**Given** a state and a question set
**When** their size is estimated
**Then** an estimate covering both together is reported
**And** it is described as approximate

**Rationale**: The budget is shared between the state and the questions, so neither can be judged alone.
No tokenizer is published, so any estimate is an approximation and pretending otherwise would mislead.

#### Scenario: Estimating an exchange

**Given** a state and a question set
**When** a caller asks for the estimate
**Then** it covers the state and the questions together

#### Scenario: The estimate does not understate

**Given** any state and question set
**When** the estimate is compared with the service's own accounting
**Then** the estimate is not smaller than what the service reported

#### Scenario: One question that cannot ever fit

**Given** a single question that exceeds the budget even alone with the state
**When** the set is submitted
**Then** the call fails locally, naming that question
**And** no exchange is made

### Requirement: A set too large for one exchange is divided and streamed

TokenBudget/split SHALL divide a question set that does not fit into batches that each fit alongside the
state, placing every question in exactly one batch, and SHALL emit each batch's answers as they arrive.

**Given** a question set too large for one exchange
**When** it is divided
**Then** each batch fits alongside the state
**And** every question is in exactly one batch

**Rationale**: Several documented uses ask far more than one exchange can carry. Dividing belongs in the
SDK, where the invariant can be stated once, rather than in every caller.

#### Scenario: Dividing a large set

**Given** a question set that exceeds the budget alongside its state
**When** it is divided
**Then** each batch fits
**And** no question is dropped and none is asked twice

#### Scenario: Answers arrive as they are ready

**Given** a divided question set
**When** the exchanges run
**Then** each batch's answers are emitted as that batch arrives
**And** a caller may begin reading before the last batch is done

#### Scenario: A batch that fails

**Given** one batch fails after Retry/schedule has spent its attempts
**When** the failure reaches the caller
**Then** the stream fails with that failure
**And** answers already emitted remain usable

#### Scenario: How many run at once is bounded and chosen

**Given** a set divided into many batches
**When** the exchanges run
**Then** the number in flight at once does not exceed a bound
**And** the caller may choose that bound

## Properties (Ring 2)

### Property: Dividing loses nothing and duplicates nothing

**Invariant**: For any state and question set, the batches' questions, taken together, are exactly the
question set — no question dropped, none repeated, none invented.

**Generator strategy**: `genOversizedSet` — constructive. Draws a state size and 1–200 questions with
sizes drawn so that the total *usually* exceeds the budget, and always includes the boundary cases by
construction: a set that fits exactly, a set one unit over, and a set where every question must occupy
its own batch. No filtering, so nothing is silently discarded. `classify` labels: batch count bucket,
whether the set fit whole.

```
forAll { (state: State, questions: QuestionSet) =>
  val batches = split(state, questions)
  batches.flatMap(_.names).sorted == questions.names.sorted &&
    batches.flatMap(_.names).distinct.size == questions.names.size
}
```

### Property: Every batch fits alongside the state

**Invariant**: For any division, each batch's estimate together with the state's is within the budget.

**Generator strategy**: `genOversizedSet` reused. Because the generator draws question sizes rather than
filtering them, a set containing one question near the whole budget arises by construction — the case
where a batch holds exactly one question. `classify` labels: largest question as a fraction of the budget.

```
forAll { (state: State, questions: QuestionSet) =>
  split(state, questions).forall(batch => estimate(state, batch) <= budget)
}
```

### Property: A question too large alone is refused, not divided

**Invariant**: When one question cannot fit alongside the state even by itself, dividing fails naming
that question, and no exchange is attempted.

**Generator strategy**: `genUnfittableQuestion` — constructive. Draws a state and one question whose size
is drawn to exceed the remaining budget by a positive margin, embedded at a drawn position among ordinary
questions, so the failure is reached from first, middle and last positions rather than only the first.
`classify` labels: position of the unfittable question.

```
forAll { (state: State, questions: QuestionSet, offender: Name) =>
  split(state, questions) match {
    case Left(failure) => failure.names(offender) && exchangesAttempted == 0
    case Right(_)      => false
  }
}
```

## Requirement ↔ Test Cross-Reference

| Requirement | Test Name (Ring 2) | Test Name (Ring 3) | Ring 5 (parity) |
|-------------|--------------------|--------------------|-----------------|
| Requirement: Questions about one state travel together | `fitting-set-makes-one-exchange` | `—` | asserted per row |
| Requirement: The size of an exchange is estimated before it is sent | `estimate-does-not-understate` | `—` | `— (estimation is pure)` |
| Scenario: One question that cannot ever fit | `unfittable-question-is-refused-not-divided` | `—` | asserted per row |
| Requirement: A set too large for one exchange is divided and streamed | `dividing-loses-nothing` + `every-batch-fits` | `—` | **asserted per row — stream types differ** |

## Cross-Backend Parity (Ring 5)

**Applies: YES.** Dividing is shared, but what a caller receives is each ecosystem's own stream type, and
the bounded concurrency is expressed differently in each.

| Behaviour | zio | ce | ox | kyo | pekko | Declared where |
|-----------|-----|----|----|-----|-------|----------------|
| Division loses nothing and duplicates nothing | yes | yes | yes | yes | yes | this spec |
| Answers emitted as each batch arrives | yes | yes | yes | yes | yes | this spec |
| A failed batch fails the stream, earlier answers stay usable | yes | yes | yes | yes | yes | this spec |
| Concurrency bound respected | yes | yes | yes | yes | yes | this spec |
| Abandoning the stream stops further exchanges | yes | yes | yes | yes | **no** | this spec + http-transport spec |

The result is a native stream on every row. Sameness of *behaviour* across five different stream types is
exactly what this ring exists to check — the shared division logic could be identical while one row's
stream adapter emits eagerly, buffers unboundedly, or swallows a failure.

**Obligation:** the parity suite asserts, per row, that the emitted answers are the same set in the same
batch order, that a failed batch fails the stream, and that the concurrency bound holds.

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A concurrency bound of zero or less | No exchange could ever run | `compileErrors` when written in source |
| Treating an estimate as an exact count | It is documented as approximate; exactness would be a false claim | `compileErrors` on any conversion to an exact token count |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| A fitting set makes exactly one exchange | Requirement: Questions about one state travel together | tier 3 — scenario test counting exchanges against the stand-in | `fitting-set-makes-one-exchange` |
| Answers do not influence one another | Scenario: Answers do not influence one another | tier 2 — questions are rendered independently, so no answer is on the path of another; **tier-justified**: independence is the service's guarantee, not this SDK's to enforce | wire-codec request fixtures |
| The estimate covers state and questions together | Requirement: The size of an exchange is estimated before it is sent | tier 2 — one function taking both, so neither can be judged alone | question-batching suite |
| The estimate does not understate | Scenario: The estimate does not understate | tier 3 — property compared against recorded service accounting; **CONFIRMED + CORRECTED 2026-09-19** (task 12.3): live `input_tokens` include a fixed ~250-token prompt scaffold the request never carries — `estimate` is `ServiceOverhead(512) + ceil(bytes/3)` (was `bytes/3`, understated small requests ~3.7×) | `estimate-does-not-understate` |
| An unfittable question is refused locally, naming it | Scenario: One question that cannot ever fit + Property: A question too large alone is refused, not divided | tier 3 — property test asserting no exchange was attempted | `unfittable-question-is-refused-not-divided` |
| Division loses nothing and duplicates nothing | Requirement: A set too large for one exchange is divided and streamed + Property: Dividing loses nothing and duplicates nothing | tier 3 — property test | `dividing-loses-nothing` |
| Every batch fits alongside the state | Property: Every batch fits alongside the state | tier 3 — property test | `every-batch-fits` |
| Answers are emitted as batches arrive | Scenario: Answers arrive as they are ready | tier 3 — parity suite observing emission order per row | cross-backend parity suite |
| A failed batch fails the stream, earlier answers stay usable | Scenario: A batch that fails | tier 3 — parity suite per row | cross-backend parity suite |
| The concurrency bound is respected and chosen by the caller | Scenario: How many run at once is bounded and chosen | tier 3 — parity suite counting concurrent exchanges against the stand-in | cross-backend parity suite |
| Concurrency observations rest on a deterministic scheduler | Scenario: How many run at once is bounded and chosen | tier 3 — the stand-in exchange parks each batch on the VirtualTime clock, so the number in flight is observed at a held point rather than sampled while running | `DeterministicClockSuite` + the batching parity suite built on it |

## Implementation Anchors

The one place in this spec where code identifiers appear.

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| Token estimator | pure function | `typesafe4s-core` | Conservative by design; documented as approximate. Budget is ~32,000 units shared across state and questions |
| Division | pure function | `typesafe4s-core` | Returns batches or names the question that cannot fit |
| Streaming surface | per-row facade | `typesafe4s-client/{zio,ce,ox,kyo,pekko}` | `ZStream`, `fs2.Stream`, Ox `Flow`, Pekko `Source` |
| Bounded concurrency | shared runtime over the carrier | `typesafe4s-client/shared` | Expressed once; each facade adapts it to its stream type |
| Property suites | munit `ScalaCheckSuite` | `typesafe4s-core/src/test/scala/` | Division and estimation are pure, so these live in core |
| Parity assertions | shared suite over the carrier | `integration-tests/shared/src/test/scala/` | `sbt parityAll` |
