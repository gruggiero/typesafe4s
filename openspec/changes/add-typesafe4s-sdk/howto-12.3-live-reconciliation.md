# How-to — task 12.3: live wire-fact reconciliation

> Confirm against a live key: the 429 retry-after header names, 529 semantics,
> and the `GET /v1/models` body shape; correct the codecs and specs if they
> differ.

This is the procedure for discharging the four MUST-CONFIRM items that no
credentialless run can settle. It requires one thing this environment never
had: a real `TYPESAFE_API_KEY`.

## What is being confirmed

| # | Fact | Current assumption | Asserted in code at | Asserted in spec at |
|---|------|--------------------|---------------------|---------------------|
| 1 | Retry-delay header names | `Retry-After` carries seconds, `retry-after-ms` carries millis; lookup is case-insensitive; blank/unparseable = unnamed | `TypesafeException.scala` — `retryDelay` (the two `header(...)` calls, ~lines 190–195) | `specs/retry-policy/spec.md`, `specs/error-model/spec.md` |
| 2 | Overload status semantics | HTTP `529` means overloaded; it maps to `TypesafeException.Overloaded` and is retryable by default (`retryableStatuses.contains(529)`) | `TypesafeException.fromResponse` (`case 529`), `RetryPolicy` | `specs/error-model/spec.md` — flagged "deliberate superset … MUST-CONFIRM 529" |
| 3 | `GET /v1/models` body shape | `{"data":[{"id","display_name","created_at"}, …]}`; `display_name` → `description`, `created_at` → `releaseDate` | `typesafe4s-core/…/Models.scala` — `decodeResponse` (strict decoder; wrong shape = `ResponseValidation`) | `specs/client-configuration/spec.md` (A5) |
| 4 | Estimator margin | `TokenBudget.estimate` = `ceil(rendered UTF-8 bytes / 3)` never understates the service's reported `input_tokens` | `typesafe4s-core/…/TokenBudget.scala` — `BytesPerToken = 3` | `specs/question-batching/spec.md`, `openspec/concepts/token-budget.md` |

## The machinery already in place

Two suites in `integration-tests/published-shared` — one source compiled and
run on all five published rows:

- **`LiveReadinessSuite.live-exchange-per-row`** — one real `systemOneDynamic`
  ask; asserts answers non-empty and usage reported.
- **`LiveWireFactSuite.live-wire-fact-reconciliation`** — the reconciliation
  run. Per credentialed execution it:
  - calls `client.listModels` — the strict decoder reads the real body; a
    shape mismatch **fails the test naming the divergence** (fact 3);
  - probes `GET /v1/models` with a raw `Transport` (bypassing status mapping),
    recording `raw.status` and every header whose name contains `retry`
    (facts 1 & 2 — *observed*, not forced);
  - performs three exchanges of varied size (small / CJK+emoji / larger) and
    compares `TokenBudget.estimate` against `usage.inputTokens` per exchange —
    understatement **fails**, absent accounting **fails** (fact 4);
  - prints the record: `[live-reconciliation] LiveReconciliation(modelsDecoded=…, retryAdviceHeaders=…, statusesSeen=…, understatedEstimates=…, unreportedAccounting=…)`.

The gate: `LiveGate.credentialed` consults `TYPESAFE_API_KEY` only; absent or
blank → skip. `LiveGate.config` resolves the full config; an invalid sibling
setting (e.g. unparseable `TYPESAFE_LOG_LEVEL`) with the key present → the
test **fails loudly**, never skips.

## Environment

```bash
export TYPESAFE_API_KEY="<the real key>"        # required — opens the gate
export TYPESAFE_BASE_URL="https://api.typesafe.ai"   # optional; default shown
export TYPESAFE_DEFAULT_MODEL="jev-latest"           # optional
export TYPESAFE_LOG_LEVEL="info"                     # optional; valid values:
                                                     # debug info warn|warning error
```

Do not paste the key into files, tickets, or CI logs. `ApiKey.toString`
redacts, and `InvalidConfiguration` details are scrubbed — but keep the key
in the environment, never in command text that gets logged.

## Step 1 — run the suite

All five rows at once (recommended — one credentialed run is five observations):

```bash
sbt parityAll
```

Or a single row when iterating:

```bash
sbt "integrationTestsZio/Test/testOnly typesafe4s.integration.LiveReadinessSuite"
sbt "integrationTestsZio/Test/testOnly typesafe4s.integration.LiveWireFactSuite"
```

Expected healthy output per row: `Total 35, Failed 0, Passed 35, Skipped 0`
(the two previously-skipped tests now run). In the `LiveWireFactSuite` output,
find the record line:

```
[live-reconciliation] LiveReconciliation(modelsDecoded=Right(…), retryAdviceHeaders=List(…), statusesSeen=List(…), understatedEstimates=List(), unreportedAccounting=List())
```

Copy that line verbatim into `openspec/changes/add-typesafe4s-sdk/state.md`
under the MUST-CONFIRM ledger.

## Step 2 — read the record

For each fact, the record either confirms or reports "not observed". Both are
honest outcomes — the suite cannot force the service to rate-limit you.

**Fact 3 — body shape.** `modelsDecoded=Right(n)` means the strict decoder
accepted the real body. Confirmed. If it shows `Left(…)` the run still fails
the test, but `modelsRawBody` carries the body that was actually observed —
that is the shape `Models.decodeResponse` must be corrected to → Step 3,
correction A.

**Fact 1 — retry-advice headers.** `retryAdviceHeaders` lists the names seen
on the probe response. If it contains `Retry-After` or `retry-after-ms`, that
name is confirmed. If empty *and* `statusesSeen` contains no 429/529, the fact
was **not observed** — record "not observed on GET /v1/models", do not mark
confirmed. To force an observation, see "Inducing a rate limit" below.

**Fact 2 — 529 semantics.** `statusesSeen` containing 529 confirms the service
emits it. Absence means **not observed** — the `Overloaded` member stays a
deliberate superset with the MUST-CONFIRM note intact. Do not remove it on
absence alone; absence proves nothing.

**Fact 4 — estimator margin.** `understatedEstimates` empty + `unreportedAccounting`
empty = confirmed for the three sampled sizes. If `unreportedAccounting` is
non-empty, the service reports no `input_tokens` — the margin is unobservable;
record that as the finding. If `understatedEstimates` is non-empty, the divisor
is wrong → Step 3, correction D.

## Step 3 — corrections, if the service diverges

Each correction follows the same loop: fix the asserting code → fix the
asserting spec's wording → fix the tests/generators that encode the old fact →
record the correction and the date in the spec → re-run rings.

- **A. Models shape wrong.** Fix `Models.decodeResponse` (field names/required
  set) and the `ModelsListingSuite` fixtures; amend `client-configuration` spec
  A5 with the observed shape.
- **B. Retry-after names wrong.** Fix the `header(...)` names in
  `TypesafeException.retryDelay`; update the generators in
  `ErrorModelProperties`; amend `retry-policy`/`error-model` specs.
- **C. Overload status differs** (e.g. service uses 503 or 529 means something
  else). Fix the `case 529` arm in `fromResponse`, the default
  `retryableStatuses`, `ErrorModelProperties` `RefusalCase`, and the
  error-model spec's 529 paragraph.
- **D. Estimator understates.** Change `BytesPerToken`/the formula in
  `TokenBudget.scala`; update `question-batching` spec and
  `openspec/concepts/token-budget.md`; note which payloads broke the floor
  (multi-codepoint sequences are the named residual risk).

After any correction: `sbt check`, `sbt testUnit`, `sbt parityAll` (with the
key — the reconciliation test must pass on the corrected facts), then amend
the spec text to record the confirmed-on date.

## Inducing a rate limit (optional, to force facts 1–2)

The built-in probe only observes `GET /v1/models`. To see 429/529 on an ask,
hit the endpoint directly:

```bash
curl -sS -D - -o /dev/null \
  -H "Authorization: Bearer $TYPESAFE_API_KEY" \
  "${TYPESAFE_BASE_URL:-https://api.typesafe.ai}/v1/models"
```

`-D -` dumps response headers — look for `Retry-After`, `retry-after-ms`, or
differently named delay headers, and the status line. Repeat in a burst to
provoke 429. If the service answers 529 anywhere, its `Overloaded` mapping is
confirmed.

## Step 4 — close the loop

1. Paste the `[live-reconciliation]` record into `state.md`.
2. Tick `tasks.md` item 12.3.
3. For each of the four facts, either mark the MUST-CONFIRM entry **confirmed**
   (with the observed values) or record "not observed — suite cannot force"
   for unforcible ones. Do not mark unobserved facts confirmed.
4. If corrections were made, the spec/code that asserts each corrected fact
   must carry the correction — that is the task's "correct the codecs and
   specs if they differ" clause.
5. Commit the ledger/spec updates separately from any code correction.

## Failure modes cheat-sheet

| Symptom | Meaning | Action |
|---------|---------|--------|
| Tests still skip with the key set | key is whitespace-only, or not exported into the sbt environment | `env \| grep TYPESAFE` — set it before launching sbt, not inside |
| `credential present but live config invalid: …` | a sibling env var is malformed (usually `TYPESAFE_LOG_LEVEL`) | fix or unset the named variable — this is F1 working as designed |
| `ResponseValidation` on `listModels` | body shape diverged | correction A |
| `unreported` non-empty | no `input_tokens` reported | record finding; margin fact unobservable |
| `understated` non-empty | `bytes/3` is too small | correction D |
| `statusesSeen=List(200)` only | no 429/529 observed | record "not observed"; optionally induce via curl |
