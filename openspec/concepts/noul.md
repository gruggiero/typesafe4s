# Concept: Noul

## Concept specification

```
concept Noul
purpose
    report how probable it is that a yes/no claim about the state holds, so that code can
    threshold on a number instead of interpreting a word
state
    claim: Noul -> Instructions                # the yes/no question, in full
    meaning: Noul -> (yes: Entry, no: Entry)   # optional clarification of each side
    belief: Noul -> Probability                # a single number in [0, 1]
actions
    ask [ claim: Instructions ; meaning: (Entry, Entry) ] => [ belief: Probability ]
operational principle
    a belief near 1 is a confident yes, near 0 a confident no, and near 0.5 genuine
    uncertainty; the number is both the answer and the certainty, so nothing further is
    reported alongside it
```

> **Noul reports no separate confidence, and this is not an oversight.** The probability *is* the certainty. Any
> spec, type or test that gives a Noul answer a confidence member contradicts the API. See
> [Confidence](confidence.md), which applies only to Choice and Score.

## Implementation map

| Element | Code |
|---|---|
| state *claim / meaning* | `Noul` (question-model spec) |
| state *belief* | `NoulAnswer` (question-model spec) |
| action *ask* | `SystemOne.renderTyped` / `SystemOne.renderDynamic` / `SystemOne.decodeTyped` (question-model spec) |

## Value domains

| Domain | Values / source | Confirmed |
|---|---|---|
| Wire discriminator | `"type": "noul"` | 2026-09-17, from docs |
| Criteria keys | `true` / `false` on the wire; documented as yes/no meanings, both optional | 2026-09-17, from docs |
| Answer field | `noul`, a number in [0, 1] | 2026-09-17, from docs |
| Constraints | none on shape; no maximum, no minimum | 2026-09-17, from docs |

## Synchronizations

None. Noul stands alone — it produces no distribution for [Confidence](confidence.md) to read.

## Deviations from the pattern

- No code exists yet.
