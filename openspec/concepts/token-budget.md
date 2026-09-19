# Concept: TokenBudget

## Concept specification

```
concept TokenBudget
purpose
    keep an exchange inside the model's shared budget, and split one that does not fit, so that
    a caller can ask about far more than one request can carry
state
    limit: TokenBudget -> Tokens              # shared across subject AND questions together
    estimate: TokenBudget -> Payload -> Tokens
    batches: TokenBudget -> Payload -> ordered list of Payload
actions
    fit [ subject ; asked ] => [ fits: Boolean ; estimate: Tokens ]
    split [ subject ; asked ] => [ batches: list of asked-subsets ]
    split [ one question that alone exceeds the limit ] => [ error: "this question cannot fit with this subject" ]
operational principle
    the budget covers the subject and every question at once, so adding questions eats the same
    allowance the subject does; when the set is too large, the subject is repeated across several
    exchanges and each question appears in exactly one of them
```

**Every question lands in exactly one batch.** Not zero (silently dropped) and not two (paid for twice). This is
the invariant a property test must pin.

**A question that cannot fit is a local failure.** If one question plus the subject exceeds the limit, no split
helps. That is rejected before any network call, naming the offending question — the server would reject it anyway,
and failing locally is faster and clearer.

## Implementation map

| Element | Code |
|---|---|
| state *limit* | `typesafe4s.TokenBudget.limit` (core) |
| state *estimate* | `typesafe4s.TokenBudget.estimate` → `typesafe4s.TokenEstimate` (core; conservative, `approximateTokens` is the only numeric view) |
| action *fit* | `typesafe4s.TokenBudget.fit` → `TokenBudget.Fit` (core) |
| action *split* | `typesafe4s.TokenBudget.split` → `Either[InvalidQuestion, List[Batch]]` (core; `Batch` ctor `private[typesafe4s]`) |
| batch execution | `typesafe4s.client.BatchedClient.systemOneBatched`/`batched` per published row over `internal/BatchRun` (session triple: `next`/`close`; `CMeter`-bounded workers, `CChannel` results, abandon flag + signal) |

## Value domains

| Domain | Values / source | Confirmed |
|---|---|---|
| Shared limit | ~32,000 tokens (~150,000 English characters) across subject and questions | 2026-09-17, from docs — the docs say "approximately" |
| Tokenizer | **not published.** No tokenizer is documented or distributed. | **CONFIRMED 2026-09-19 (task 12.3)** — the service counts a fixed ~250-token prompt scaffold on top of ~4-bytes/token content accounting, so `estimate = ServiceOverhead(512) + ceil(bytes/3)`. Still approximate: conservative (over-estimate) so a "fits" verdict is safe; do not claim exactness. |
| Cost model | input tokens billed, output tokens free | 2026-09-17, from docs |
| Rate limits | 250,000 tokens/sec; 1,200 requests/min | 2026-09-17, from docs — bounds the concurrency of a split |

## Synchronizations

```
sync BudgetedEvaluation
when {
    Evaluation/ask: [ subject ; asked ] => [ answered ]
}
where {
    TokenBudget/fit: [ subject ; asked ] => [ fits: true ]
}
then {
    Evaluation/ask: [ subject ; asked ]
}

sync SplitOversizedEvaluation
when {
    Evaluation/ask: [ subject ; asked ] => [ answered ]
}
where {
    TokenBudget/fit: [ subject ; asked ] => [ fits: false ]
}
then {
    TokenBudget/split: [ subject ; asked ] => [ batches ]
    Evaluation/ask: [ subject ; batch ]        # once per batch, bounded concurrency
}
```

impl: `BatchedClient.systemOneBatched` → `BatchRun.start` — `TokenBudget.split` once, then one
`Ask.run` per batch under `CMeter` bounded concurrency, results published in arrival order.
Deviation: results are emitted as each batch resolves rather than gathered, so a caller consumes answers before
every batch completes. A batch that fails after its retries are exhausted fails the stream; answers already
emitted stay valid.

## Deviations from the pattern

- Emission is arrival-ordered streaming (`Evaluation[AnswerSet]` per batch), not a gathered list — declared
  and implemented; a caller abandoning the stream signals `BatchRun.close`, which on the Future-backed
  (pekko) row cannot preempt already-issued exchanges (declared divergence).
- Splitting repeats the subject in every batch, so a large subject with many questions costs the subject's tokens
  once per batch. That is inherent to an API with no batch endpoint, and callers should be told.
