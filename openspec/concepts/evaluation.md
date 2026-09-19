# Concept: Evaluation

## Concept specification

```
concept Evaluation [State, QuestionId]
purpose
    obtain typed judgments about one piece of state in a single exchange, so that code can
    act on values rather than parse prose
state
    subject: Evaluation -> State                   # the text, object or array being judged
    asked: Evaluation -> QuestionId -> Question    # a non-empty, named set
    answered: Evaluation -> QuestionId -> Answer   # one answer per asked id
    judge: Evaluation -> ModelName                 # which model answered
    cost: Evaluation -> Usage                      # input/output tokens, either may be unreported
    trace: Evaluation -> RequestId                 # the only handle support has; may be absent
actions
    ask [ subject: State ; asked: QuestionId -> Question ; judge: ModelName ]
        => [ answered: QuestionId -> Answer ; cost: Usage ; trace: RequestId ]
    ask [ asked: {} ] => [ error: "a request must ask at least one question" ]
    ask [ ... ] => [ error: "the model rejected the request" ]          # 400/422
    ask [ ... ] => [ error: "the answer did not fit the agreed shape" ]  # 2xx, bad body
    listJudges [] => [ judges: set of (ModelName, description, releaseDate) ]
operational principle
    if you ask a set of questions about one subject, you receive exactly one answer per asked
    id, evaluated independently and in parallel; asking more questions costs little more time
    than asking one, and no answer becomes context for another
```

**Independence is load-bearing.** Questions in one Evaluation are evaluated in isolation. A caller who needs
answer A to form question B needs two Evaluations. A caller who merely *might* not need B should still ask it —
speculative fan-out is cheaper than a second exchange.

## Implementation map

<!-- EMPTY BY DESIGN. Filling a row is part of implementing the spec named beside it.
     registry-check.sh verifies backticked symbols against the source tree — do not add one early. -->

| Element | Code |
|---|---|
| state *subject* | `StateEncoder[A]` renders the `state: A` parameter to `Entry`/`Json` for the request body (wire-codec spec) |
| state *asked / answered* | `QuestionSet` / `AnswerSet` (question-model spec) |
| state *cost / trace* | `Usage` (wire-codec spec) / `RequestId` (error-model spec) — both carried on `AnswerSet` |
| action *ask* | `Client.systemOne` / `Client.systemOneDynamic` (effect-portability spec); wire: `SystemOne.renderTyped` / `SystemOne.renderDynamic` / `SystemOne.decodeTyped` (question-model spec) |
| action *listJudges* | `Client.listModels` → `internal.ClientCio.listModels` (`GET /v1/models`) → `Models.decodeResponse` strict decode → `Model(name, description, releaseDate)` (client-configuration spec) |
| runtime host | `typesafe4s.client.Client` — one shared implementation over the effect carrier: `internal.ClientCio` + `internal.Ask` on `kyo.compat.CIO`, adapted per row by `LoweredClient` (effect-portability spec) |

## Value domains

| Domain | Values / source | Confirmed |
|---|---|---|
| Model names | `jev-latest`, `jev-preview`, `jev-1.13.0` — <https://docs.typesafe.ai/models> | 2026-09-17, from docs |
| Default model | `jev-latest` | 2026-09-17, from docs |
| Endpoint | `POST /v1/systemone`; models at `GET /v1/models` | 2026-09-17, from docs |
| Shared token budget | ~32,000 tokens across subject and questions combined | 2026-09-17, from docs — approximate by the docs' own wording |
| `GET /v1/models` response shape | name, description, release date | **MUST-CONFIRM** — described in prose, no schema published. Confirm against a live key (task 12.3). |
| Subject shapes | text, JSON object, or JSON array | 2026-09-17, from docs |

## Synchronizations

Evaluation is the hub concept; the syncs that drive it are declared where their other party lives —
[TokenBudget](token-budget.md), [Retry](retry.md), [Credential](credential.md).

## Deviations from the pattern

- The API exposes no streaming and no batch endpoint, so `ask` is a single atomic exchange. Fan-out across several
  exchanges is [TokenBudget](token-budget.md)'s job, not this concept's.
