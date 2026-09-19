# Implementation Order

Dependency analysis below. Three specs pairs are mutually dependent at the *operand* level — the
resolution for each is recorded under "Mutual dependencies", because the order here is the exact
order apply follows and nobody should have to re-derive it.

## Current position

- Next spec: 10/10 `specs/release-readiness/spec.md` — credential-gated live suite per row, live
  wire-fact reconciliation (task 12.3), per-row examples, README, CI matrix + snapshot publishing
- Last commit: spec 9/10 json-adapters checkpoint (`ee32763`)
- State ledger: `state.md` <!-- created at apply Step 0, rewritten at every step boundary -->

## Dependency Analysis

"Depends On" lists **implementation concepts introduced by other specs in this change** — the things
a spec's code must name to compile. Behavioural concepts (Evaluation, Noul, Retry, …) live in the
registry and are realized by these specs; they are not ordering edges.

| # | Spec | Introduces | Depends On (concepts) | Complexity |
|---|------|-----------|----------------------|------------|
| 1 | `specs/error-model/spec.md` | TypesafeException, RequestId | — (foundational: pure core, no spec dependencies) | medium |
| 2 | `specs/wire-codec/spec.md` | Json, Entry, StateEncoder, Usage | TypesafeException — the decoder's `ResponseValidation` member (1). Implements the Question/Answer **data shapes** (3's concepts) as codec operands | high |
| 3 | `specs/question-model/spec.md` | Question, NoulAnswer, ChoiceAnswer, ScoreAnswer, AnswerSet | Entry — question instructions hold it (2); the request renderer and response decoder (2) — its Ring-2 oracle exercises them | high |
| 4 | `specs/retry-policy/spec.md` | RetryPolicy, Attempt | TypesafeException — classification reads the closed family (1); `Clock` seam (pre-existing, not spec-introduced) | medium |
| 5 | `specs/http-transport/spec.md` | Transport, HttpRequest/HttpResponse | TypesafeException — raises Timeout/ConnectionFailed (1); the decoder (2); the retry loop — "abandoning stops the repeat loop" (4); ApiKey (7's concept, lands here as an operand for Credential/present) | high |
| 6 | `specs/effect-portability/spec.md` | Carrier, Facade (`Client[F]`, `LoweredClient[F]`) | The whole pipeline: family (1), codecs (2), surfaces (3), loop (4), SPI (5); TypesafeConfig (7's concept, lands here as an operand — client construction takes it) | high |
| 7 | `specs/client-configuration/spec.md` | TypesafeConfig, ApiKey | `Client`/`LoweredClient`/facades — scoped-construction parity needs a client that exists (6); Transport (5); codecs — the models listing decodes (2) | medium |
| 8 | `specs/question-batching/spec.md` | TokenEstimate, Batch | Client + per-row facades — the stream surface is an adapter on them (6); QuestionSet/AnswerMap (3); retry loop (4); `Clock` seam | high |
| 9 | `specs/json-adapters/spec.md` | — (four adapter modules; no new core types) | `Entry`/`StateEncoder` (2) — the shape each adapter converts into; the checked `Entry.fromJson` path (2) | medium |
| 10 | `specs/release-readiness/spec.md` | — (release machinery: live suite, examples, README, CI; no new SDK types) | Everything — the live suite and examples exercise the whole published surface; the reconciliation verifies the codecs (2, 7) and the estimator (8) | medium |

### Mutual dependencies, and how each is broken

The concept graph is not a clean DAG. Three pairs need each other's types; in every case the
resolution is the same move — the *operand* type lands with the spec that first needs it, and the
owning spec still owns and verifies the behaviour:

1. **wire-codec ↔ question-model.** The request encoder consumes `Map[String, Question[?]]` and the
   response decoder produces the answer types — so the codec cannot exist without the model's data
   shapes. Conversely the model's constructors take `Entry` instructions and its own Ring-2 oracle
   calls `decode`/`render`. Whichever ran first would drag most of the other along; the ordering
   chosen keeps **each spec's headline in its own spec**: spec 2 implements the wire machinery plus
   the plain `Noul`/`Choice`/`Score` case classes and answer products *as operands* (no `AnswerOf`
   match type, no named-tuple surface, no inline limits, no `Mirror.derived`), and spec 3 then lands
   the risky type-level surface as a separately gated unit. This mirrors tasks.md §2→§3→§4.
2. **http-transport → client-configuration (operand).** "An exchange carries what the service
   requires" needs the authorization value `Credential/present` produces, which means unwrapping
   `ApiKey` — an opaque type only its own code can open. `ApiKey` therefore lands at spec 5 as an
   operand of the request builder; spec 7 still owns and verifies the redaction contract.
3. **client-configuration ↔ effect-portability.** The config's scoped-construction parity needs a
   `Client` to construct; the `Client` needs a `TypesafeConfig` to be constructed from. Spec 7
   cannot complete before spec 6, so `TypesafeConfig`/`ApiKey`/resolution land at spec 6 as operands
   of the composition, and spec 7 verifies resolution order and redaction (oracle re-run) plus the
   construction/release parity rows and the models listing — all of which are only exercisable once
   facades exist.

### Deferred Ring-5 rows

error-model's cross-reference marks "local failures make no request" as *asserted per row
(construction differs)*. Client construction does not exist until spec 7, so that parity row is
recorded here as landing in the parity suite when construction does — the parity suite accretes rows
per spec and `sbt parityAll` re-runs all of them, so spec 7's pass covers it. Everything else in
error-model's parity table (raise a member per row; ZIO names the family in its channel) is
exercisable at spec 1 via the carrier plus the ZIO facade's error-channel alias, which this spec's
own anchors assign to it.

## Ring Applicability

Ring numbering is **this project's**: 0 compile · 1 lint · 1.5 architecture · 2 property tests ·
3 mutation · 4 formal · 5 cross-backend parity · 8 adversarial review. Cells are facts from
`openspec/capability-profile.md`:

- R1 is *advisory* everywhere — scalafmt + `-Werror -Wunused` only; no Scalafix/WartRemover.
- R1.5 is *advisory* everywhere — no architecture rules installed; semanticdb is off.
- R3 is ⏭️ everywhere — no stryker4s; unavailable in this project.
- R4 is ⏭️ everywhere — no Stainless; static limits are enforced at tier 1 (`inline if` +
  `compiletime.error`) and discharged by R0 + compile-negative tests.
- R2 is ✅ for every code-changing spec. R8 is ✅ for every code-changing spec. Neither is a judgment call.
- R5 is ✅ where the spec's own parity section says YES.

| # | Spec | R0 | R1 | R1.5 | R2 | R3 | R4 | R5 | R8 | Gate tier |
|---|------|----|----|------|----|----|----|----|----|-----------|
| 1 | error-model | ✅ | advisory | advisory | ✅ | ⏭️ | ⏭️ | ✅ | ✅ | two gates |
| 2 | wire-codec | ✅ | advisory | advisory | ✅ | ⏭️ | ⏭️ | ⏭️ | ✅ | two gates |
| 3 | question-model | ✅ | advisory | advisory | ✅ | ⏭️ | ⏭️ | ⏭️ | ✅ | two gates |
| 4 | retry-policy | ✅ | advisory | advisory | ✅ | ⏭️ | ⏭️ | ✅ | ✅ | two gates |
| 5 | http-transport | ✅ | advisory | advisory | ✅ | ⏭️ | ⏭️ | ✅ | ✅ | two gates |
| 6 | effect-portability | ✅ | advisory | advisory | ✅ | ⏭️ | ⏭️ | ✅ | ✅ | two gates |
| 7 | client-configuration | ✅ | advisory | advisory | ✅ | ⏭️ | ⏭️ | ✅ | ✅ | two gates |
| 8 | question-batching | ✅ | advisory | advisory | ✅ | ⏭️ | ⏭️ | ✅ | ✅ | two gates |
| 9 | json-adapters | ✅ | advisory | advisory | ✅ | ⏭️ | ⏭️ | ⏭️ | ✅ | two gates |
| 10 | release-readiness | ✅ | advisory | advisory | ⏭️ none declared | ⏭️ | ⏭️ | ✅ | ✅ | one gate |

## Complexity Guide

Every spec here is medium or high — each introduces new types, and several introduce new error paths
or concurrency behaviour. **No spec qualifies as simple** (no new types, ≤1 new method on an existing
trait, no new error variants), so no combined gates and — since every spec introduces a concept —
no waivers are available.

## Gate Tiers

| # | Spec | Tier | Reason |
|---|------|------|--------|
| 1 | error-model | two gates | sealed failure family + status mapping — the error algebra every later spec consumes; a wrong shape means reworking their oracles |
| 2 | wire-codec | two gates | introduces Json/Entry/StateEncoder/Usage plus the codec contract and the operand data shapes — the largest single type-surface commitment |
| 3 | question-model | two gates | the SDK's defining type surface: `Question[A]` GADT, `AnswerOf` match type, named-tuple surface, compile-time limits — highest risk of a wrong public API |
| 4 | retry-policy | two gates | new policy types plus the loop's timing semantics — timing behaviour deserves an oracle review before implementation |
| 5 | http-transport | two gates | the public SPI plus cancellation semantics, which differ per row and are easy to get subtly wrong |
| 6 | effect-portability | two gates | `Client[F]`/`LoweredClient`/`Facade` — the published shape of the whole SDK plus the composition of everything before it |
| 7 | client-configuration | two gates | introduces `TypesafeConfig` + `ApiKey`'s redaction contract — a security property (secret never rendered) worth an independent oracle review |
| 8 | question-batching | two gates | budget estimation plus bounded-concurrency streaming per row — the subtlest concurrency behaviour in the change |
| 9 | json-adapters | two gates | the refusal mechanism is a real design decision — the encoder is total and Entry recursively forbids numbers/booleans, so the contract must be approved before the oracle assumes a shape |
| 10 | release-readiness | one gate | no new SDK types and no new error paths — release machinery (live suite, examples, README, CI); a single contract gate suffices |

## Implementation Sequence

Process each spec in this exact order. For each spec:

0. Record the BASELINE SHA (`git rev-parse HEAD`) + inventory snapshot
1. Read `openspec/concept-inventory.md` — import existing concepts
2. Write the TYPED CONTRACT in the owning module's test sources; compile it  **[GATE 1]**
3. Write the TEST ORACLE from the spec; run it for polarity  **[GATE 2]**
4. Implement, then run rings 0 → 1 → 1.5 → 2
5. RING 8 adversarial review in a fresh-context subagent
6. Ring 5 (parity) as applicable
7. Concept delta check; update `openspec/concept-inventory.md`
8. Commit, then STOP for human validation before the next spec

The baseline is load-bearing: every diff a spec's rings compute is taken against it. Do not skip
ahead, do not batch-implement, one spec at a time.

| # | Spec | Baseline SHA | Commit |
|---|------|--------------|--------|
| 1 | error-model | — | — |
| 2 | wire-codec | — | — |
| 3 | question-model | — | — |
| 4 | retry-policy | — | — |
| 5 | http-transport | — | — |
| 6 | effect-portability | — | — |
| 7 | client-configuration | — | — |
| 8 | question-batching | — | — |
| 9 | json-adapters | — | — |
| 10 | release-readiness | — | — |

- [x] 1. `specs/error-model/spec.md` — the sealed failure family, status→error mapping, request-id propagation
- [x] 2. `specs/wire-codec/spec.md` — zero-dependency JSON AST/parser/printer, `Entry`/`StateEncoder`/`Usage`, request/response codecs (+ question/answer data shapes as operands)
- [x] 3. `specs/question-model/spec.md` — `Question[A]` GADT, `AnswerOf`, named-tuple and dynamic surfaces, `AnswerMap`, compile-time limits, `Mirror`-derived Choice
- [x] 4. `specs/retry-policy/spec.md` — `RetryPolicy`/`Attempt`, classification, the retry loop over `CIO` + `Clock`
- [x] 5. `specs/http-transport/spec.md` — `HttpRequest`/`HttpResponse`, `Transport` SPI, JDK transport with cancellation bracket, per-attempt timeout, stand-in exchange (+ `ApiKey` operand)
- [x] 6. `specs/effect-portability/spec.md` — `Client[F]`/`LoweredClient` composition, per-row facades, ZIO typed channel, completeness check, parity machinery (+ `TypesafeConfig` operand)
- [x] 7. `specs/client-configuration/spec.md` — config resolution + redaction verification, scoped/manual construction parity, models listing
- [x] 8. `specs/question-batching/spec.md` — token estimation, budget-aware splitting, bounded-concurrency batched call, per-row stream adapters
- [x] 9. `specs/json-adapters/spec.md` — circe, zio-json, jsoniter-scala and upickle adapters; faithful conversion, dotted-path refusal, per-adapter dependency isolation (tasks §11; resolves design open question 2 — all four ship)
- [ ] 10. `specs/release-readiness/spec.md` — credential-gated live suite per row, live wire-fact reconciliation (task 12.3), per-row examples, README, CI matrix + snapshot publishing (tasks §12.2–12.7)
