# Concept: Confidence

## Concept specification

```
concept Confidence
purpose
    collapse the shape of a belief distribution into one number, so that code can decide
    WHETHER to act separately from deciding WHAT to do
state
    distribution: Confidence -> Outcome -> Probability
    certainty: Confidence -> Certainty     # one number in [0, 1]
actions
    derive [ distribution: Outcome -> Probability ] => [ certainty: Certainty ]
operational principle
    a distribution concentrated on one outcome yields a certainty near 1 and a flat one yields a
    certainty near 0; thresholding on that number lets the same answer be acted on automatically,
    confirmed with a human, or escalated, according to what the action costs
```

**Two axes, not one.** The answer says *what*; the certainty says *whether to act on it*. The documented use is a
three-way split — act automatically, seek confirmation, or escalate — with the boundaries set by what the action
costs, not by the model. A read-only action tolerates far lower certainty than a destructive one.

**The caller is never locked in.** `certainty` is a convenience computed from a distribution the caller already
has. A caller whose situation calls for a different measure — entropy, top-two margin, mass above a level — is
expected to compute it from `distribution`. Requirements must not assume `certainty` is the only available signal.

## Implementation map

| Element | Code |
|---|---|
| state *distribution* | `ChoiceAnswer` / `ScoreAnswer` probabilities members (question-model spec) |
| state *certainty* | `ChoiceAnswer` / `ScoreAnswer` confidence members (question-model spec) |
| action *derive* | `SystemOne.decodeResponse` — the API returns it; the SDK reports it rather than computing it (question-model spec) |

## Value domains

| Domain | Values / source | Confirmed |
|---|---|---|
| Range | [0, 1] | 2026-09-17, from docs |
| Exact formula | **not published.** Docs state only that it is "computed from the probability distribution" and reflects how peaked it is. | **MUST-CONFIRM** — treat as opaque. Do not reimplement or assert an exact value in a test. |
| Applies to | Choice and Score only — **never Noul** | 2026-09-17, from docs |
| Illustrative gates | below 0.5 escalate; 0.5–0.9 confirm for high-stakes; above 0.9 proceed | 2026-09-17, from docs — an example, not a rule |

## Synchronizations

```
sync ConfidenceFromChoice
when {
    Choice/ask: [ question ; options ] => [ selected ; spread ; certainty ]
}
then {
    Confidence/derive: [ distribution: spread ]
}

sync ConfidenceFromScore
when {
    Score/ask: [ question ; levels ] => [ position ; legend ; spread ; certainty ]
}
then {
    Confidence/derive: [ distribution: spread ]
}
```

impl: *not implemented.*
Deviation: the API computes `certainty` server-side and returns it with the answer, so in practice this sync is
already discharged on arrival — the SDK reports rather than derives. Recorded here because the *dependency*
(certainty is a function of the distribution, and of nothing else) is what specs may rely on.

## Deviations from the pattern

- No code exists yet.
- Noul deliberately has no certainty. A spec that treats "every answer has a confidence" as uniform is wrong.
