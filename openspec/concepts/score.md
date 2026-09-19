# Concept: Score

## Concept specification

```
concept Score [Level]
purpose
    place the state on an ordered rubric of described levels, and expose where between the
    levels the belief actually sits, so that code can threshold on a spectrum
state
    question: Score -> Instructions
    levels: Score -> Level -> Entry          # ordered, low to high; 2..10 of them
    position: Score -> Rational              # probability-weighted mean of level numbers
    legend: Score -> Level -> Entry          # the levels echoed back by number
    spread: Score -> Level -> Probability    # over all levels, summing to 1
    certainty: Score -> Certainty            # see Confidence
actions
    ask [ question: Instructions ; levels: ordered Entry list ]
        => [ position: Rational ; legend ; spread ; certainty ]
    ask [ fewer than 2 levels ] => [ error: "a Score needs at least 2 levels" ]
    ask [ more than 10 levels ] => [ error: "a Score accepts at most 10 levels" ]
operational principle
    a position of 1.3 does not mean "level 1, slightly" — it means the belief is spread between
    levels in a way whose weighted mean is 1.3; the same position can arise from very different
    spreads, so position alone never justifies a decision
```

> **`position` is a weighted mean, not a verdict.** A confident level 1 and an even split between 0 and 2 can both
> read 1.3. Any requirement that thresholds on position alone must say why the spread does not matter there, or
> read `certainty` too.

**Levels describe situations, not degrees.** "Broken, but a workaround exists" steers the model; "moderately
severe" does not. Each level is judged independently — the model does not see level numbers or neighbours.

## Implementation map

| Element | Code |
|---|---|
| state *question / levels* | `Score` / `Score.of` (question-model spec) |
| state *position / legend / spread* | `ScoreAnswer` (question-model spec) |
| state *certainty* | `ScoreAnswer` confidence member — see Confidence |
| action *ask* | `SystemOne.renderTyped` / `SystemOne.renderDynamic` / `SystemOne.decodeTyped` (question-model spec) |
| level-count enforcement | `Score.of` — compile error when statically known; `QuestionSet.validated` — runtime gate (question-model spec) |

## Value domains

| Domain | Values / source | Confirmed |
|---|---|---|
| Wire discriminator | `"type": "score"` | 2026-09-17, from docs |
| Criteria shape | ordered array of level descriptions | 2026-09-17, from docs |
| Answer fields | `score`, `legend`, `probabilities`, `confidence` | 2026-09-17, from docs |
| Level count | minimum 2, maximum 10 | 2026-09-17, from docs |
| Level numbering | zero-based; `legend` and `probabilities` are keyed by level number | 2026-09-17, from docs |

## Synchronizations

```
sync ConfidenceFromScore
when {
    Score/ask: [ question ; levels ] => [ position ; legend ; spread ; certainty ]
}
then {
    Confidence/derive: [ distribution: spread ]
}
```

Declared in [confidence.md](confidence.md).

## Deviations from the pattern

- No code exists yet.
