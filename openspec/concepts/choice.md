# Concept: Choice

## Concept specification

```
concept Choice [Option]
purpose
    select one option from a closed, unordered set and expose how the belief was spread across
    all of them, so that code can branch exhaustively and know how near the call was
state
    question: Choice -> Instructions
    options: Choice -> Option -> Entry         # description per option; may be absent
    selected: Choice -> Option                 # the highest-probability option
    spread: Choice -> Option -> Probability    # over all options, summing to 1
    certainty: Choice -> Certainty             # see Confidence
actions
    ask [ question: Instructions ; options: Option -> Entry ]
        => [ selected: Option ; spread: Option -> Probability ; certainty: Certainty ]
    ask [ options with more than 255 members ] => [ error: "a Choice accepts at most 255 options" ]
    ask [ options: {} ] => [ error: "a Choice needs at least one option" ]
operational principle
    the answer is always one of the options you supplied — never a value outside the set — so a
    closed set in the request is a closed set in the response, and code can match on it exhaustively
```

**The closed set is the point.** Because the model cannot answer outside the supplied options, the option set is a
type, not a suggestion. When real inputs may fall outside the list, supply an explicit `other` or
`none_of_the_above` option rather than letting the model pick the least-wrong one.

## Implementation map

| Element | Code |
|---|---|
| state *question / options* | `Choice` / `Choice.of` / `Choice.derived` (question-model spec) |
| state *selected / spread* | `ChoiceAnswer` (question-model spec) |
| state *certainty* | `ChoiceAnswer` confidence member — see Confidence |
| action *ask* | `SystemOne.renderTyped` / `SystemOne.renderDynamic` / `SystemOne.decodeTyped` (question-model spec) |
| option-set enforcement | `Choice.of` — compile error when statically known; `QuestionSet.validated` — runtime gate (question-model spec) |

## Value domains

| Domain | Values / source | Confirmed |
|---|---|---|
| Wire discriminator | `"type": "choice"` | 2026-09-17, from docs |
| Criteria shape | object mapping option key to description or `null` | 2026-09-17, from docs |
| Answer fields | `choice`, `probabilities`, `confidence` | 2026-09-17, from docs |
| Maximum options | 255 | 2026-09-17, from docs |
| Option key vocabulary | caller-defined; no reserved words documented | 2026-09-17, from docs |

## Synchronizations

```
sync ConfidenceFromChoice
when {
    Choice/ask: [ question ; options ] => [ selected ; spread ; certainty ]
}
then {
    Confidence/derive: [ distribution: spread ]
}
```

Declared in [confidence.md](confidence.md).

## Deviations from the pattern

- No code exists yet.
- The design intends `Choice` to be constructible from a Scala 3 `enum`, making `selected` a domain value rather
  than a string. That is a *realization* choice; the concept is unchanged either way.
