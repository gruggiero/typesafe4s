# Spec Lint Report

Run 2026-09-17, after migrating all eight specs from the older `spec-driven` format to this schema.

## Mechanical pre-pass

**openspec validate --strict**:
```
✓ change/add-typesafe4s-sdk
Totals: 1 passed, 0 failed (1 items)
```

**spec-lint.sh**:
```
spec-lint: 8 spec file(s), 0 FAIL, 16 WARN
```

Zero FAILs. The 16 remaining warnings are **all W3** — no W1 (vague words), no W5 (impossibility enforced only
by tests), no W7 (code identifiers in clauses), and no W8.

- **W3** is advisory by construction: it asks the author to confirm that a negative requirement has a scenario
  whose input it forbids. Each negative requirement here has one (they are listed under check 15). The warning
  cannot be cleared structurally — it is a prompt, not a defect.
- **W8 was a checker bug, and it has been fixed.** It reported concepts as "named nowhere else" when they were
  named, *non-deterministically*: `printf ... | grep -q` under `set -o pipefail` made `grep -q` exit at the
  first match, `printf` take SIGPIPE, and the pipeline report 141. Instrumenting the real run caught it —
  `rc=141` on one spec, `rc=0` on another, from the same code and equally valid input. It was fixed upstream (here: a here-string instead of a pipeline) and the fix re-copied into this repo's
  vendored schema, along with the same hazard in `registry-check.sh`'s `action_declared`. A second, unrelated
  bug in the same check produced bogus concept names such as `Gen ` from a cell like `Gen [A]`, because the
  bracket strip could reintroduce a trailing space; that is fixed too.

  With the check working, it found **one genuine problem in these specs**: `client-configuration` cited `Retry`
  while no requirement in it was Retry's behaviour. The citation has been removed with a note saying why. The
  count went 9 (flaky) → 1 (genuine) → 0.

**registry-check.sh**:
```
registry-check: OK (0 implementation-map tokens verified, 25 spec concept references checked, 0 EARS references checked, 0 weak binding(s) to tighten)
```

All 25 `Concept` / `Concept/action` references cited across the eight specs resolve to registry files. (26 before the decorative `Retry` citation was removed from `client-configuration`.)

### Applicability (CONTEXT block, verbatim)

```
spec-lint: CONTEXT — repository facts. These decide each conditional check's
           APPLICABILITY. Compliance remains yours; applicability does not.
  schema                openspec/schemas/verified-scala3  v5
  behavioural registry  openspec/concepts/             PRESENT (8 concepts)
    -> check 17 ALTITUDE **APPLIES**. "N/A" is not a valid verdict for it.
       F10 checks the structural half; W7 lists code-identifier candidates;
       reading the clause prose for behavioural altitude is still your job.
  type inventory        openspec/concept-inventory.md  PRESENT (17 typed rows)
    -> check 6 (reused concepts exist) **APPLIES**.
  capability profile    openspec/capability-profile.md PRESENT
    -> checks 3 (testable with detected stack) and 18 (CONCURRENCY) **APPLY**
       deterministic test kit detected: VirtualTime
```

| Conditional check | CONTEXT says | Verdict allowed |
|---|---|---|
| 3 · testable with detected stack | capability profile PRESENT | **APPLIES** |
| 6 · reused concepts resolved | type inventory PRESENT (17 rows) | **APPLIES** |
| 17 · ALTITUDE | behavioural registry PRESENT (8 concepts) | **APPLIES** — N/A is not a permitted verdict |
| 18 · CONCURRENCY | **VirtualTime detected** | **APPLIES** — and is now satisfiable |

> **Check 18 was the decisive one in the first run of this report (2026-09-17, earlier).** It failed three
> specs, because no deterministic time control existed on any row. A VirtualTime clock has since been built —
> once, against the carrier, green on all five rows — and this report is re-run against it. The history is
> kept in "What changed" below rather than erased, because the gap and its closure are the useful record.

## Results

Checks 1–18 are the schema's list. `✅` = satisfied; `⚠️` = satisfied with a recorded caveat; `❌` = blocking.
Rows that are uniformly `✅` across all eight specs (1, 1b, 2, 5, 9, 13, 14, 17) are stated once here rather
than repeated eight times; every spec was judged against them individually.

**Uniform across all eight specs:**

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | Every requirement and scenario uses the clause form |
| 1b | SHALL/MUST before first `**Given**` | ✅ | Mechanical F1 clean on all eight |
| 2 | Then observable | ✅ | Every Then names a returned value, a raised failure, a rendered byte sequence, or a build outcome |
| 5 | New concepts declared | ✅ | Each spec carries a "Concepts Introduced" table |
| 9 | No vague words | ✅ | **W1 count: 0.** "valid", "correct", "appropriate", "fast", "reasonable" deliberately avoided; e.g. error-model says "a failure to reach the service" rather than "no valid response" |
| 13 | Consumer-facing surface asserted | ✅ | Request and response shapes asserted against recorded fixtures in wire-codec; call surfaces asserted in question-model |
| 14 | Error variants type-feasible | ✅ | Every asserted failure is a member of the sealed family defined in error-model |
| 17 | Altitude respected | ✅ | **W7 count: 0.** Code identifiers confined to Implementation Anchors in all eight specs |

### Spec: specs/question-model/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1c | Per-variant behaviour-preservation | ✅ | One scenario per question kind (Noul, Choice, Score) |
| 3 | Scenarios testable | ✅ | Compile-negative via munit `compileErrors`; properties via `ScalaCheckSuite` — both detected |
| 4 | Error paths specified | ✅ | Unmatched option, unasked name, wrong-kind read |
| 6 | Reused concepts resolved | ✅ | 5 concepts cited, all resolve |
| 7 | Generator strategies | ✅ | 4 properties, each with a constructive strategy and classify labels |
| 8 | Temporal trigger/response | n/a | Ring 5 here is parity, not telemetry; no temporal properties |
| 10 | Unreachable claims proven | ✅ | Every impossibility claim is tier 1 with a compile-negative row |
| 11 | Type-widening impact | n/a | No public type's variant set changes |
| 12 | Proof obligations complete | ✅ | 14 rows; every requirement named by title (F7 clean, no ordinals) |
| 15 | Adversarial scenarios | ✅ | "A name that was never asked is refused"; "A limit broken only at run time still fails safely" |
| 16 | MUST-CONFIRM marks | n/a | No externally-sourced value domain in this spec |
| 18 | Concurrency deterministic | n/a | No concurrent behaviour |

**Verdict: PASS**

### Spec: specs/wire-codec/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1c | Per-variant scenarios | ✅ | One wire scenario per question kind, plus one per answer kind |
| 3 | Scenarios testable | ✅ | Recorded fixtures plus properties; both supported by the detected stack |
| 4 | Error paths specified | ✅ | Non-fitting body, unknown kind marker, absent token counts |
| 6 | Reused concepts resolved | ✅ | 4 concepts cited, all resolve |
| 7 | Generator strategies | ✅ | 3 properties; `genJson` reaches escaping by construction |
| 8 | Temporal trigger/response | n/a | — |
| 10 | Unreachable claims proven | ✅ | Structured instructions cannot be flattened — tier 1 |
| 11 | Type-widening impact | n/a | — |
| 12 | Proof obligations complete | ✅ | 13 rows; one tier-justified (a zero-dependency claim is a build fact, not a type) |
| 15 | Adversarial scenarios | ✅ | "Adapters do not drag each other in"; "A successful response whose body does not fit" |
| 16 | MUST-CONFIRM marks | n/a | Wire shapes here are from documented examples reproduced as fixtures |
| 18 | Concurrency deterministic | n/a | — |

**Verdict: PASS**

### Spec: specs/error-model/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1c | Per-variant scenarios | ✅ | One scenario per documented refusal class |
| 3 | Scenarios testable | ✅ | Enumerated refusals, so every member is reached on a short run |
| 4 | Error paths specified | ✅ | This spec *is* the error paths |
| 6 | Reused concepts resolved | ✅ | 3 concepts cited, all resolve |
| 7 | Generator strategies | ✅ | 2 properties; "present but empty" trace reached by construction |
| 8 | Temporal trigger/response | n/a | — |
| 10 | Unreachable claims proven | ✅ | Closed family, exhaustiveness is a build concern — tier 1 |
| 11 | Type-widening impact | ⚠️ | The family is introduced, not widened. Growing it later widens every caller's match — noted for the change that does so |
| 12 | Proof obligations complete | ✅ | 11 rows; every requirement named |
| 15 | Adversarial scenarios | ✅ | "A refusal carrying no trace" asserts no blank or invented value |
| 16 | MUST-CONFIRM marks | ✅ | Overload (529) marked — the API reference names it, the reference SDK folds it into the general range |
| 18 | Concurrency deterministic | n/a | Failure mapping is pure |

**Verdict: PASS**

### Spec: specs/client-configuration/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1c | Per-variant scenarios | ✅ | One per resolution source; one per rendering context |
| 3 | Scenarios testable | ✅ | Rendering contexts enumerated, so every path is covered |
| 4 | Error paths specified | ✅ | No credential; scope failing mid-work |
| 6 | Reused concepts resolved | ✅ | 3 concepts cited, all resolve |
| 7 | Generator strategies | ✅ | 2 properties; "present but empty" reached by construction |
| 8 | Temporal trigger/response | n/a | — |
| 10 | Unreachable claims proven | ✅ | The secret cannot be read out — tier 1, opaque with no accessor |
| 11 | Type-widening impact | n/a | — |
| 12 | Proof obligations complete | ✅ | 10 rows; one tier-justified (tier 1 stops *reading* the secret but cannot stop a badly written future rendering — no lint rule is installed that would) |
| 15 | Adversarial scenarios | ✅ | "A secret embedded in a larger structure"; "Rendering a failure" |
| 16 | MUST-CONFIRM marks | ✅ | The models listing's shape is prose-only |
| 18 | Concurrency deterministic | ⚠️ | Release-on-scope-exit is asserted per row by the parity suite, observed as an effect rather than by timing — no clock needed |

**Verdict: PASS**

### Spec: specs/effect-portability/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1c | Per-variant scenarios | ✅ | Declared-divergence table asserts each of the five rows individually |
| 3 | Scenarios testable | ✅ | The parity suite already runs green on all five rows (build stub) |
| 4 | Error paths specified | ✅ | Missing operation fails the build; a row not run is reported as not run |
| 6 | Reused concepts resolved | ✅ | 3 concepts cited, all resolve |
| 7 | Generator strategies | ✅ | 2 properties; `genProgram` compares against an independently computed model rather than against itself |
| 8 | Temporal trigger/response | n/a | — |
| 10 | Unreachable claims proven | ✅ | Carrier never appears in a published type — tier 1 |
| 11 | Type-widening impact | n/a | — |
| 12 | Proof obligations complete | ✅ | 14 rows; two tier-justified at tier 8 ("exactly one implementation" and "adapts rather than reimplements" are structural judgements no type or single test expresses) |
| 15 | Adversarial scenarios | ✅ | "A result not obtained is not a result"; "The check is shown to be capable of failing" |
| 16 | MUST-CONFIRM marks | n/a | — |
| 18 | Concurrency deterministic | ⚠️ | Parity assertions observe effects, not timing. The completeness check (task 10.5) **does not exist yet** — recorded in the profile as Ring 5's missing guarantee |

**Verdict: PASS**

### Spec: specs/retry-policy/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1c | Per-variant scenarios | ✅ | One per repeatable and non-repeatable refusal class |
| 3 | Scenarios testable | ✅ | Timing scenarios read the `Clock` seam and supply the VirtualTime clock |
| 4 | Error paths specified | ✅ | Bound reached; abandonment; last-failure reporting |
| 6 | Reused concepts resolved | ✅ | 2 concepts cited, both resolve |
| 7 | Generator strategies | ✅ | 3 properties; the bound-shorter-than-first-wait case is reached by construction |
| 8 | Temporal trigger/response | n/a | — |
| 10 | Unreachable claims proven | ✅ | No bound-specific failure member exists to report — tier 2 |
| 11 | Type-widening impact | n/a | — |
| 12 | Proof obligations complete | ✅ | 12 rows, including one explicitly marked **UNRESOLVED** for the clock |
| 15 | Adversarial scenarios | ✅ | "A wait that would reach the bound"; "The caller goes away mid-wait" |
| 16 | MUST-CONFIRM marks | ✅ | The headers naming a service-requested wait are prose-only |
| 18 | Concurrency deterministic | ✅ | Waits, growth, ceiling, jitter and the total bound are all observed on virtual time. No wall-clock assertion remains |

**Verdict: PASS** *(was FAIL — the deterministic-clock gap closed)*

### Spec: specs/http-transport/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1c | Per-variant scenarios | ✅ | Abandonment asserted per row, including the documented no-op |
| 3 | Scenarios testable | ✅ | Header and seam scenarios use the stand-in exchange; the per-attempt allowance is measured on virtual time |
| 4 | Error paths specified | ✅ | Attempt runs out of time; abandonment; a row that cannot abandon |
| 6 | Reused concepts resolved | ✅ | 3 concepts cited, all resolve |
| 7 | Generator strategies | ✅ | 2 properties; abandon points are enumerated rather than timed, so they do not race |
| 8 | Temporal trigger/response | n/a | — |
| 10 | Unreachable claims proven | ✅ | Core reaches no network — tier 1, the capability is not on its path |
| 11 | Type-widening impact | n/a | — |
| 12 | Proof obligations complete | ✅ | 11 rows, including one explicitly marked **UNRESOLVED** for the clock |
| 15 | Adversarial scenarios | ✅ | "Abandoning stops the repeat loop too"; "A row that cannot abandon says so" |
| 16 | MUST-CONFIRM marks | n/a | — |
| 18 | Concurrency deterministic | ✅ | The allowance is measured against the `Clock` seam; abandonment was already asserted by enumerated points rather than by timing |

**Verdict: PASS** *(was FAIL — the deterministic-clock gap closed)*

### Spec: specs/question-batching/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1c | Per-variant scenarios | ✅ | Emission, failure and bounding asserted per row |
| 3 | Scenarios testable | ✅ | Division and estimation are pure; the concurrency bound is observed at a point held on virtual time |
| 4 | Error paths specified | ✅ | Unfittable question; failing batch |
| 6 | Reused concepts resolved | ✅ | 3 concepts cited, all resolve |
| 7 | Generator strategies | ✅ | 3 properties; boundary sets (fits exactly, one over, one-per-batch) included by construction |
| 8 | Temporal trigger/response | n/a | — |
| 10 | Unreachable claims proven | ✅ | An estimate cannot be converted to an exact count — tier 1 |
| 11 | Type-widening impact | n/a | — |
| 12 | Proof obligations complete | ✅ | 10 rows, including one explicitly marked **UNRESOLVED** for the scheduler |
| 15 | Adversarial scenarios | ✅ | "One question that cannot ever fit"; "A batch that fails" |
| 16 | MUST-CONFIRM marks | ✅ | No tokenizer is published, so the estimator's margin must be measured against real responses |
| 18 | Concurrency deterministic | ✅ | The stand-in exchange parks each batch on the VirtualTime clock, so the number in flight is observed at a held point rather than sampled |

**Verdict: PASS** *(was FAIL — the deterministic-concurrency gap closed)*

## Summary

| Spec | Verdict | Blocking Issues |
|------|---------|-----------------|
| specs/question-model/spec.md | **PASS** | none |
| specs/wire-codec/spec.md | **PASS** | none |
| specs/error-model/spec.md | **PASS** | none |
| specs/client-configuration/spec.md | **PASS** | none |
| specs/effect-portability/spec.md | **PASS** | none |
| specs/retry-policy/spec.md | **PASS** | none *(was FAIL)* |
| specs/http-transport/spec.md | **PASS** | none *(was FAIL)* |
| specs/question-batching/spec.md | **PASS** | none *(was FAIL)* |

**Overall: 8 PASS, 0 FAIL.** `implementation-order.md` is unblocked.

## What changed

The first run of this report recorded **5 PASS, 3 FAIL**. All three failures were one gap: no deterministic
time or concurrency test kit existed on any backend row, so the timing requirements in `retry-policy`,
`http-transport` and `question-batching` could only have been wall-clock assertions, which check 18 fails.

That gap is now closed, and how it was closed matters for anyone reading these specs later:

**An ecosystem test kit was deliberately not used.** Cats Effect's `TestControl` and ZIO's `TestClock` would
each have covered one row; Ox and Kyo ship no equivalent at all. Taking that route would have forced timing
tests to be written per row — breaking the one-suite-for-every-row rule that Ring 5 rests on — and would have
left two rows with no deterministic option whatsoever.

Instead the shared runtime reads time through a `Clock` seam, and a VirtualTime clock is implemented **once,
against the carrier**, so the same deterministic suite compiles and runs on every row. `DeterministicClockSuite`
covers: time not advancing on its own, exact advancement, a sleeper held until its deadline passes, a deadline
reached exactly, one advance releasing only the sleepers that became due, and a non-positive sleep not parking.
**9 tests, green on all five published rows**, verified 2026-09-17.

Building it surfaced a genuine divergence the shared suite would otherwise have hidden: **the Ox binding's
`unsafeRun` takes an `ExecutionContext` and the other four do not.** The suite supplies one and marks it
`@unused` so the rows that ignore it still compile under `-Werror -Wunused`. That is recorded in the capability
profile, and it is a small illustration of why the parity ring exists — the code compiled on four rows and
failed on the fifth.

**What this does not cover.** The clock makes *time* deterministic. It does not make thread interleaving
deterministic: a property asserting that at most N exchanges run at once still depends on the stand-in exchange
parking each batch at a held point, which the batching spec's obligations now state explicitly. Nothing here
claims more than virtual time actually provides.

---

## Addendum — 10-spec run (change-level verification, 2026-09-19)

Two specs were added at change-level verification — `json-adapters` (tasks §11) and
`release-readiness` (tasks §12.2–12.7) — after the original eight were found complete but the
proposal's adapter and release commitments had no spec coverage. This addendum records the re-run;
the eight-spec findings above are unchanged and still stand.

**Mechanical pre-pass:**

```
openspec validate --strict:  ✓ change/add-typesafe4s-sdk valid
spec-lint: 10 spec file(s), 0 FAIL, 23 WARN
registry-check --only-change: OK (64 impl-map tokens, 28 spec concept references)
```

All 23 warnings are W3 (advisory negative-requirement prompts, per the note above) — **0 FAIL, no
W1/W5/W7/W8** on either new spec after authoring fixes:

- `release-readiness` initially produced **F7** (the wire-facts requirement named by no proof
  obligation), **W1** (vague word "correct"), and **W8** ×2 (`Credential`, `Token Budget` cited in
  Concepts Used but never named in a SHALL). All fixed by naming `Credential/resolve` and
  `Token Budget/estimate` in the requirement bodies and adding a proof-obligation row that names the
  requirement itself.
- `json-adapters` was clean on first pass.

**Judgment notes on the new specs:**

- `json-adapters` carries a genuine **gate-1 design question** recorded in its proof obligations:
  `StateEncoder.encode` is total (`A => Entry`) and `Entry` recursively forbids numbers and
  booleans, so an unconditional implicit for a whole library JSON type cannot honestly exist. The
  spec requires refusal-naming-the-position but deliberately leaves the mechanism (checked
  conversion / opt-in wrapper / narrowed given) to the typed contract.
- `release-readiness` declares **no Ring-2 properties** — its obligations are build/release facts
  (file existence, CI matrix coverage, live reconciliation verdicts), discharged at tier 3/4. This
  is stated in the spec rather than padded with pseudo-properties.
- `release-readiness`'s live-reconciliation scenarios are MUST-CONFIRM by nature until a key exists;
  that is the requirement's own content (task 12.3), not an unmarked assumption.
