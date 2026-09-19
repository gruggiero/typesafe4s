# Concept: Retry

## Concept specification

```
concept Retry
purpose
    survive transient failure without unbounded waiting, so that a caller sees a durable result
    or a timely error rather than a hang
state
    attemptsLeft: Retry -> Natural
    delay: Retry -> Duration            # grows, bounded, with jitter subtracted
    budgetLeft: Retry -> Duration       # total for the whole call, including the first attempt
    retryable: Retry -> Outcome -> Boolean
actions
    classify [ outcome: Outcome ] => [ retryable: Boolean ]
    schedule [ attempt: Natural ; advice: Duration ] => [ delay: Duration ]
    schedule [ delay would reach budgetLeft ] => [ exhausted: lastOutcome ]
    override [ policy ] => [ policy ]    # for one call only
operational principle
    a transient failure is retried after a growing, jittered delay, honouring any delay the
    server itself named; a durable failure is surfaced at once; and the whole call stops when the
    budget would be reached, reporting the last failure rather than a budget-specific one
```

**The failure reported is the last attempt's**, never a synthetic "retries exhausted" error — the caller needs to
know *what* went wrong, not merely that it kept going wrong.

**A delay that would reach the budget is not started.** The budget is not a cancellation after the fact; it is a
precondition on beginning to wait.

**Interruption stops everything.** If the caller goes away mid-delay, no further attempt is made and the remaining
budget is not consumed. On backends that cannot cancel this is impossible, and that divergence is declared, not
hidden — see the Ring 5 parity obligation in `openspec/config.yaml`.

## Implementation map

| Element | Code |
|---|---|
| state *attemptsLeft / delay / budgetLeft* | *not implemented — retry-policy spec* |
| state *retryable* | *not implemented — retry-policy spec and error-model spec* |
| action *classify* | *not implemented — retry-policy spec* |
| action *schedule* | *not implemented — retry-policy spec; one implementation over the effect carrier* |
| action *override* | *not implemented — retry-policy spec* |

## Value domains

| Domain | Values / source | Confirmed |
|---|---|---|
| Retryable statuses | 408, 429, 500–599 | 2026-09-17, from the Python SDK reference |
| Durable statuses | 400, 401, 403, 404, 422 | 2026-09-17, from the Python SDK reference |
| Default attempts | 2 retries after the initial attempt | 2026-09-17, from docs |
| Default backoff | 0.5s initial, doubling, capped at 5.0s | 2026-09-17, from docs |
| Default jitter | 0.25 — a fraction SUBTRACTED from each delay | 2026-09-17, from docs |
| Default total budget | 30s per call, including the first attempt and all delays | 2026-09-17, from docs |
| Server-named delay | `Retry-After` and `retry-after-ms` response headers | **MUST-CONFIRM** — header names taken from SDK prose, not from a published schema. Confirm against a live 429 (task 12.3). |
| 529 Overloaded | documented in the HTTP reference; absent from the Python exception tree | **MUST-CONFIRM** — we treat it as a distinct, retryable case. Confirm the real status and body (task 12.3). |

## Synchronizations

```
sync RetryTransientExchange
when {
    Evaluation/ask: [ subject ; asked ] => [ error: outcome ]
}
where {
    Retry/classify: [ outcome ] => [ retryable: true ]
    Retry/schedule: [ attempt ; advice ] => [ delay ]
}
then {
    Evaluation/ask: [ subject ; asked ]
}
```

impl: *not implemented.*
Deviation: none intended. Note that the retry loop needs a clock and a cancellable sleep, which is why it lives
once in shared code over the effect carrier rather than per backend.

## Deviations from the pattern

- No code exists yet.
- **No deterministic time control is available on any row** (`openspec/capability-profile.md`). Until one is added,
  testing this concept's timing means wall-clock waits or a waiver. This is the highest-value setup gap.
