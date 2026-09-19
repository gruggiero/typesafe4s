---
name: openspec-next-spec
description: >
  Implement and verify the next spec in the depth-first implementation order.
  Reads implementation-order.md, finds the next unchecked spec, records a
  baseline SHA, writes a compiled TYPED CONTRACT and a spec-derived TEST
  ORACLE behind human gates, implements, then runs the verification rings
  (0 compile → 1 lint → 1.5 architecture → 2 property tests → 8 adversarial
  review → 3 mutation → 4 formal → 5 telemetry), updates the project concept
  inventory, and stops for human validation before the next spec.
globs:
  - "openspec/changes/*/implementation-order.md"
  - "openspec/changes/*/implementation-progress.md"
  - "openspec/changes/*/specs/**/*.md"
  - "openspec/concept-inventory.md"
  - "openspec/capability-profile.md"
  - "**/src/main/scala/**/*.scala"
  - "**/src/test/scala/**/*.scala"
metadata:
  generatedBy: verified-scala3-schema/5.0.0
---

# Next Spec Skill — Depth-First Implementation (schema v5)

## Overview

Implements ONE spec through the full ring pipeline, then STOPS for human
validation.

**Invocation**: `/opsx:next-spec`, or as part of `/opsx:apply`.

## What changed from v4 — read this before following the steps

| v4 | v5 |
|----|----|
| Pseudocode in `openspec/changes/<c>/pseudocode/*.scala`, "verified" by a top-level `sbt compile` that never compiled it | **Typed contract** in `<module>/src/test/scala/.../typecontract/`, compiled by `sbt <module>/Test/compile` |
| Ring 2 tests written AFTER the implementation | **Test oracle written BEFORE**, from the spec, with a polarity run proving it can fail |
| Per-change `concept-inventory.md` | **Project-scoped** `openspec/concept-inventory.md` |
| No adversarial review | **Ring 8**, mandatory, in a fresh-context subagent |
| Commands hardcoded (`sbt test`, `munit.ScalaCheckSuite`) | Commands and frameworks read from **`openspec/capability-profile.md`** |

**Never hardcode a build command or a test framework in this workflow.** Read
`openspec/capability-profile.md`. The v4 driver told agents to write
`munit.ScalaCheckSuite` tests in a project that uses ScalaTest and has no munit
on the classpath, and to run Stainless in a repository with no Stainless
module. Both instructions had been followed for months.

## THE INVARIANT

Code is CORRECT iff every requirement is BOUND to an enforcement mechanism,
each mechanism is RESOLVED to an artifact that exists, and each artifact is
DISCHARGED — observed to run green in THIS session. Obtained, not recalled.

> **NEVER LET A CLAIM OUTRUN ITS EVIDENCE.**
> A verdict you did not obtain in this session is not a verdict.
> "N/A", "passes", "not applicable" and "already handled" are CLAIMS.

A ring you did not run is not a ring that passed. A ring whose tooling is not
installed is ADVISORY, not ✅.

---

## Step 0 — Determine the next spec, record the BASELINE

**READ THE LEDGER FIRST.** `openspec/changes/<CHANGE>/state.md` is this
change's carry-forward file — current spec, baseline SHA, gate/ring status,
capability commands, known scanner gaps, next action — rewritten at every
step boundary. If it exists it answers the startup questions in ~50 lines;
read it before anything else. Cross-check it against the first unchecked
item (one grep, not a full read of the 20KB order file):

```bash
grep -n '^- \[ \]' openspec/changes/<CHANGE>/implementation-order.md | head -1
```

If the ledger is absent or contradicts the checkbox list, reconstruct
position from implementation-order.md and implementation-progress.md —
then write the ledger. From here on it is maintained at every boundary
(see **The state ledger** below).

1. Find the first UNCHECKED item (`- [ ]`) in the Implementation Sequence —
   from state.md if current, else the grep above.
2. Read that spec file — **targeted, not whole.** Specs run 20–60KB. List
   the headings first (`grep -n '^##' <spec>`), then read the sections this
   step needs: Ring Applicability, Gate tier, Concepts tables, Proof
   Obligations. Same lookup doctrine as the concept inventory — a whole
   file read to answer three questions is the avoidable cost this step
   exists to remove.
3. Read its **Ring Applicability** row and its **Gate tier** (two gates /
   combined / waiver). Both drive everything below.
4. Read `openspec/capability-profile.md` **ONCE** — copy the command table
   (compile, test, lint, format, mutation, formal, telemetry; detected test
   framework and kits) into `state.md` under `## Capability commands`.
   Later steps use the ledger copy. Re-read the profile only when a
   recorded command actually fails — staleness shows as a failed command,
   not as a reason to re-read per step.
5. Probe Metals once — `scanner/metals-call.sh probe` — and record the
   outcome in `state.md` (`metals:` field). Discovery finds
   `.metals/mcp.url` (metals-start.sh) AND `.metals/mcp.json` (an IDE-open
   instance you are already paying for), and verifies the endpoint serves
   THIS workspace — a foreign project's instance is rejected, never
   trusted. `down` means impact-scan/removal-audit/Ring-8 queries run the
   grep fallback; say so where a verdict depends on it.

If ALL items are checked:
```
All specs have been implemented and verified.
Run /opsx:archive to complete the change.
```

**BASELINE — before touching anything:**

- The working tree must be CLEAN. If it is dirty, STOP and resolve with the
  human. Do not start a spec on a dirty tree — the baseline would be
  meaningless and every diff below would be wrong.
- Record `git rev-parse HEAD` as the BASELINE SHA in
  `implementation-progress.md`.
- Take the inventory snapshot:
  ```bash
  openspec/schemas/verified-scala3/scanner/scan.sh . \
    --output openspec/changes/<CHANGE>/inventory-snapshots/<spec>-before.md
  ```

Every diff this spec's rings compute — the Ring 8 review, Ring 3 mutation
targeting, the Step 11 deltas — is `git diff <baseline>`. **Never the
unanchored working tree**, which after spec 1 contains other specs' changes.

### The state ledger — `openspec/changes/<CHANGE>/state.md`

Write it now, before any work, and rewrite it at **every step boundary** —
each gate, each ring, each paused step. A fresh context (new session, a
compaction) reads it first and resumes without re-deriving position; the
~50-line ledger replaces a cold boot through a 30–70KB generated summary
plus re-reads. Keep it under ~60 lines — it is a ledger, not a narrative;
implementation-progress.md keeps the prose.

```markdown
# State — <change>
spec: <n>/<m> <spec-name>        # or "done — all specs implemented"
step: <current step, e.g. "0b concept check" / "awaiting Gate 2">
baseline: <sha>                  # this spec's Step-0 baseline
prior-commit: <sha>              # previous spec's Step-12 commit
gate-tier: two gates | combined | waiver
gates: gate1 pending|approved · gate2 pending|approved
rings: R0 … · R1 … · R1.5 … · R2 … · R8 … · R3 … · R4 … · R5 …
       # pending | ✅ <one-line evidence> | advisory | ⏭️ <reason> | ❌
registry: ok | excluded: <names> (--except-change/--only-change)
metals: up <url> (verified) | down — grep fallback
       # metals-call.sh verifies serverInfo.name == "<repo>-metals"; a
       # foreign workspace's instance is rejected, not trusted. Cold-index
       # caveat: get-usages needs the module compiled — "up" means the
       # endpoint verified, not that every module is indexed yet.
scanner-gaps: <concepts the inventory misses, if any; else none>
deviations: <approved deviations, open findings>
open-questions: <items flagged for the human gate; each marked
  pending|resolved:<disposition> — observed: a "flagged for the gate"
  question with no ledger entry evaporates at the checkpoint boundary>
next: <the exact next action>

## Capability commands
compile: …   test: …   lint: …   format: …   mutation: …   formal: …
test-framework: …   test-kits: …
```

Rules:

- Update it **before** the work that could be lost, not in a batch at the
  end — a compaction mid-ring is exactly when it is needed.
- A ring verdict is recorded **with its evidence line** (test counts, VC
  counts, mutation score). A verdict without evidence is a claim, and a
  resumed session must re-obtain it — the invariant is not relaxed by the
  ledger; the ledger is where obtained evidence lives.
- `open-questions` is the disposition trail for anything a spec or review
  flags "for the human gate". When a question is raised, it enters the
  ledger as `pending`; the Step-12 checkpoint lists pending items verbatim
  so the human actually answers them; an answered question becomes
  `resolved: <disposition>` — never deleted, never left silent.
- It is committed with the spec's Step-12 commit like every change
  artifact. At Step 12, rewrite it for the next spec: `spec:` advances,
  `step:` becomes `awaiting human validation`, rings reset to `pending`,
  `prior-commit:` becomes this spec's commit, `baseline:` clears (recorded
  at next Step 0). `open-questions` carries forward verbatim — items the
  human answered become `resolved: <disposition>`, unanswered ones stay
  `pending`.

## Step 0b — Concept check

**LOOK UP, DO NOT READ WHOLE.** `openspec/concept-inventory.md` is
PROJECT-scoped (~940 rows today), grows with the project, and is never
archived. Reading it end-to-end to check a handful of concepts is the single
largest avoidable token cost in this workflow — measured at ~49% of a spec
run, and rising. Look up exactly what this spec names:

1. Extract the concept names from the spec's **Implementation Anchors**,
   **Concepts Used** and **Concepts Introduced** tables — a bounded set,
   typically 5-15 names.
2. Look them all up in one pass:
   ```bash
   grep -nE '^\| `?(NameA|NameB|NameC)`? \|' openspec/concept-inventory.md
   ```
   Each hit returns that concept's full row — package, constraint, variants,
   provenance — which is everything an import needs.
3. **A name with no matching row is MISSING.** grep tells you that directly;
   it is the trigger for the branch below. Absence of a row is evidence.
   Never infer absence from having skimmed.
4. Need the file's shape (to append at Step 11, or to find which table a
   concept belongs in)? Read the headings only:
   ```bash
   grep -n '^## ' openspec/concept-inventory.md
   ```

Read the whole file ONLY when creating it or auditing it end-to-end — neither
happens during a normal spec run.

For every concept the spec names:

- verify it exists in the project source
- import it — do NOT recreate it
- if the spec EXTENDS a concept (an enum variant, a trait method), modify the
  EXISTING file rather than creating a parallel version

If a required concept is MISSING from the inventory:
```
⚠ Spec requires concept '<name>' but it is not in openspec/concept-inventory.md.
  1. It exists in source but the inventory is stale  → refresh with scan.sh
  2. An earlier spec should introduce it             → check the order
  3. It is genuinely missing                         → the dependency analysis is wrong
```
STOP and ask which applies.

Verify the spec's **Proof Obligations** table is complete: every requirement,
scenario, invariant and introduced type constraint has a declared mechanism.
If not, STOP — the spec must be fixed first.

**PUBLIC-TYPE-CHANGE IMPACT SCAN** — run when the spec aliases, widens or
changes the variant set of a PUBLIC type (see its **Type-Widening Impact**
section):
```bash
openspec/schemas/verified-scala3/scanner/impact-scan.sh <fqcn>
```
It uses a compiler-resolved reference set when a Metals endpoint is running and
falls back to a whole-tree grep by itself. **Say which path it took.** For each
catch-all site found, require one of — recorded in Proof Obligations — made
exhaustive; explicitly rejects the new variants; or justified with rationale.

This closes the blind spot where aliasing a public type to a richer enum
silently widens every downstream `case _` **with no file edit anywhere in the
diff**.

**REGISTRY GATE — BLOCKING:**
```bash
openspec/schemas/verified-scala3/scanner/registry-check.sh .
```
It must pass BEFORE you write code. When OTHER changes are in flight, their
SPEC/EARS rows fail this check and read as YOUR problem — scope the
per-change passes explicitly and record the scoping in `state.md`:

```bash
registry-check.sh . --only-change <CHANGE>
# or: registry-check.sh . --except-change <unrelated-change>
```

The Implementation-map, fold-field and findings passes always run — the
flags scope only the per-change spec/EARS passes. Exclusions print at the
top of the output; if you don't see the notice, they didn't apply.

Then, for every concept cited in **Concepts Used (behavioral)**, open its concept file and verify each state
component and action the spec RELIES ON is actually realized — read the
fold/handler the Implementation map points to. A field declared on a model
shape but never populated by the fold is NOT available. If a spec assumption
contradicts the concept file or the code, STOP and report the discrepancy
instead of implementing on top of it.

For every **MUST-CONFIRM** item: STOP and ask the human for the authoritative
data. Do NOT synthesize plausible values.

## Step 1 — TYPED CONTRACT ◄ compiled, then GATE 1

Use the `openspec-typed-contract` skill.

Generate a compile-only contract containing imports for reused concepts (exact
packages from the inventory), new domain type declarations, public method and
command signatures with `???` bodies, the error algebra, smart constructors,
`assertDoesNotCompile` stubs for every Compile-Negative Obligation, and
property/generator obligations as structured comments.

**PLACEMENT** — per the capability profile:
```
<module>/src/test/scala/<pkg>/typecontract/<SpecName>TypeContract.scala
```
Files under `openspec/changes/...` are **not** in the sbt source graph. v4 put
them there and ran a top-level `sbt compile` that never touched them, so the
human approved a skeleton nothing had type-checked. That is the specific defect
this placement fixes.

Compile it:
```bash
sbt <module>/Test/compile
```
Fix until it genuinely compiles. **Paste the success line.**

**GATE 1** — if the tier is **two gates**: STOP, present the contract (new
types, signatures, error algebra, compile-negatives), wait for explicit
approval.
If the tier is **combined**: do NOT stop; carry the contract into the single
gate at the end of Step 2. The contract is still written and still compiled —
combined means one interruption, never one less artifact.

## Step 2 — TEST ORACLE ◄ before implementation, then GATE 2

Use the `openspec-property-tests` skill. It reads the capability profile for
the DETECTED framework — generate for that framework, never an assumed one.

Write the tests NOW, from the SPEC and the approved contract only, before any
implementation exists. Tests written after an implementation mirror it; tests
written from the spec are an independent oracle.

- One test per Scenario, named after the scenario heading
- One property per **Properties (Ring 2)** invariant, using the spec's declared
  **Generator strategy**. Prefer constructive generators. This project's
  property library has **no coverage assertion** — `classify`/`collect` are
  informational and cannot fail a build, so a conditioned property whose
  antecedent is rarely generated passes vacuously. Make the interesting cases
  reachable by construction.
- Actor scenarios use the detected test kits (ActorTestKit, BehaviorTestKit,
  PersistenceTestKit, TestProbe). Never deferred to "integration testing" —
  they are on the test classpath.
- Concurrency/timeout/cancellation scenarios assert DETERMINISTIC observables
  (ordering, final state, emitted events), never wall-clock timing. If the
  profile records no deterministic test kit, that is a capability gap needing a
  setup task or an explicit waiver — it does not silently become a timing test.
- Every Requirement and Scenario has a test; every concept in **Implementation
  Anchors** and **Concepts Introduced** appears in EXECUTABLE test code
  (import, type annotation, generator type, constructor, pattern match). A
  comment naming it does not count.
- Every requirement containing "only"/"never"/"must not" gets a test feeding an
  input it forbids, asserting rejection.
- Every test cites its source: `// spec: <spec-name> — Scenario: <heading>`
- Tests must COMPILE (`sbt <module>/Test/compile`). They are EXPECTED TO FAIL
  at runtime until Step 3 — that is the point.

**ORACLE FAITHFULNESS** — a test asserting a spec-named error variant asserts
that variant exactly. Do NOT loosen to `.toString.contains(...)` /
`message.contains(...)` / `||` fallbacks to accommodate the implementation. If
the implementation cannot produce the named variant, the SPEC or the producing
signature changes — re-approved at this gate — never the oracle.

**GENERATOR FAITHFULNESS** — generators are part of the approved oracle.
Narrowing one after approval (a filter, a tightened `Range`, a dropped edge
case) is tampering exactly like loosening an assertion. In Step 6 the
IMPLEMENTATION changes — never the generators.

Produce three tables:
```
A) | Spec Heading | Test Name | Status |
B) | Concept | Source | Test Reference | Status |
C) | Obligation | Enforcement | Test/Artifact | Status |
```

**ORACLE POLARITY** — prove the oracle can fail before trusting it. Run it once
now, before any implementation, and classify every new test:

- **RED** — asserts new behavior; MUST fail at this run. A red-classified test
  that PASSES pre-implementation is a broken oracle — vacuous, tautological, or
  asserting behavior that already exists. Fix it BEFORE the gate; right now it
  proves nothing.
- **GREEN-BY-DESIGN** — behavior-preservation tests for refactors; MUST pass
  now against the OLD code and still pass after Step 3. Their pre-implementation
  pass IS their evidential value.

A test that cannot be classified does not enter the oracle.

```
| Test | Polarity | Pre-impl result |
```

**GATE 2 — STOP.** Present the property inventory, the polarity table and the
three coverage tables. Wait for explicit approval before implementing.
(Combined tier: present the Step 1 contract here too — this is the single gate.)

## Step 3 — Ring 0: implementation + compilation

Generate the full implementation satisfying the approved contract and oracle.

- Keep approved type declarations and signatures EXACTLY as approved
- Import reused concepts — do NOT recreate them
- Promote contract declarations into main sources; implement the `???` bodies
- Extend existing enums/traits in their existing files
- All matches over sealed types exhaustive — this build escalates
  `PatternMatchExhaustivity` and `MatchCaseUnreachable` to **errors**, so an
  inexhaustive match FAILS rather than warns
- Plain Scala 3 opaque types for new domain values (no Iron in this project); `sealed` on new domain traits and
  enums; `Ref` for mutable state — no `var`, no `null`, no `throw`

Run the profile's compile command. Fix, re-compile. **Max 3 iterations**, then
STOP and present the errors.

## Step 4 — Ring 1: lint

Run the lint check **scoped to this spec's baseline**:

```bash
openspec/schemas/verified-scala3/scanner/lint-diff.sh <baseline-SHA>
```

This repo's lint baseline is dirty (~90 scalafix `DisableSyntax` errors,
~111 unformatted files) — a repo-wide check fails no matter what the spec
did, and eyeballing "zero new violations" out of a wall of baseline noise is
the manual diff this script replaces. It runs the profile's lint commands
once and fails only on violations in files this spec touched (ratchet:
a touched file must lint clean even if it was dirty at baseline). If it
reports a build-level error rather than file violations, run the profile's
lint command directly — that failure is not lint noise.

Note on the profile commands it wraps: this build sets
`ThisBuild / semanticdbEnabled := true`, so `sbt scalafixAll --check` runs
the semantic rules directly — no `scalafixEnable` prelude.

Fix DisableSyntax violations (var, null, throw, return, while), unused
imports, and WartRemover violations **in files this spec touched**. Re-run
until clean. **Max 2 iterations.**

## Step 5 — Ring 1.5: architecture

Read the capability profile's static-analysis section FIRST.

- If custom architecture rules are ACTIVE: run them, fix violations.
- If the profile records them **NOT INSTALLED**: this ring is **ADVISORY**.
  Check the design's layer table by reading the imports you added, and report
  it in the checkpoint as `advisory` — **not `✅ passed`**. A ring reported as
  passing when no rule ran is a claim outrunning its evidence.

## Step 6 — Ring 2: run the test oracle ◄ MANDATORY

Ring 2 is mandatory for every spec, including pure refactorings, thread-safety
changes and internal restructurings.

Run the profile's test command.

- RED tests from Step 2 must now be GREEN. One still red is an unfinished
  implementation.
- GREEN-BY-DESIGN tests must STILL be green. One that turned red is a behavior
  regression, not a test to adjust.
- If a property is falsified: read the counterexample, fix the
  **IMPLEMENTATION**. The property is the spec. Do not weaken the property, the
  assertion, or the generator. **Max 3 iterations**, then STOP and present the
  counterexample.
- Re-verify the three cross-reference tables and close any gaps.

**SPEC UPDATE** — write the `## Requirement ↔ Test Cross-Reference` table into
the spec file, after Properties (Ring 2), one row per Requirement/Scenario in
declaration order.

## Step 7 — Ring 8: adversarial spec-compliance review ◄ MANDATORY

Tests can pass while the implementation violates the English spec. **This
project has already shipped exactly that**: in
`add-eligibility-in-between-operators` every test passed while `cmpOpToOrderOp`
silently mapped invalid operators to `OrderOp.Lte` and `cmpOpToCountOp` mapped
IN/BETWEEN to `CountOp.Gte`. Constructing `Expr.Count(..., CmpOp.IN, ...)`
directly produced wrong behavior with no guard.

**Position** — Ring 8 runs BEFORE the expensive rings (3/4/5) deliberately: it
is cheap, it frequently changes code and adds tests, and mutation must run on
FINAL code — the tests this ring adds also kill mutants, so the score recorded
in Step 8 is the true one.

**FRESH-CONTEXT MANDATE.** The review MUST be done by a reviewer with NO
implementation context. Self-review by the author context is the
confirmation-bias setup this ring exists to defeat — the author knows what the
code is supposed to do and reads that intent into it.

**Spawn a subagent** (Agent tool, `general-purpose`) whose prompt contains ONLY:

1. an instruction to follow the `openspec-adversarial-review` skill;
2. the path to the spec file;
3. the path to the typed contract;
4. the baseline SHA, with the instruction to review `git diff <baseline-SHA>`;
5. the repository path.

**Do NOT include** any narrative of what you implemented, why you chose an
approach, which tests you wrote, or what you believe the code does. Those are
precisely the inputs that defeat the review. Do not summarize the diff for the
reviewer — let it read the diff.

The reviewer must:
1. Compare EVERY requirement against the diff, requirement by requirement,
   **including private helpers** — that is where violations hide.
2. Run `scanner/danger-scan.sh <baseline-SHA>` for mechanical candidates, then
   judge each hit: fallback/default mappings; `case _` in domain logic
   returning a VALID value for invalid input; "unreachable"/"cannot happen"
   comments without type-level proof; partial functions; `.get`; unsafe
   `head`/`last`; `asInstanceOf`; `@unchecked`; swallowed errors; default
   parameters masking missing data. Every hit is fixed or justified with
   `// danger-scan:allow <reason>`.
3. Ask whether a PUBLIC API can construct a state a requirement forbids —
   exercising DIRECT construction paths, not just the sanctioned pipeline.
4. Ask whether the code could pass the CURRENT tests while violating the spec.
5. Verify the oracle was not tampered with since Step 2.

**Output** — one verdict per requirement, PASS / PARTIAL / FAIL, each with
evidence (`file:line`), why the tests missed it, and the fix class, strongest
first: type-level prevention → smart constructor / explicit rejection →
targeted test. **Never "add a defensive fallback"** — silent mapping to valid
behavior IS the bug, not the fix.

**Gate**: any FAIL → fix it (re-running Rings 0–2 as affected) before
proceeding. Any PARTIAL → fix it, or carry it to the checkpoint for explicit
human approval. No checkpoint is complete with a FAIL outstanding.

If a fresh context is genuinely impossible, SAY SO — the checkpoint carries
`fresh-context: no` and the human weighs the verdicts accordingly. Do not
quietly self-review and report it as Ring 8.

## Step 8 — Ring 3: mutation testing (if available)

Read the capability profile. If mutation tooling is absent, skip with a stated
correctness impact.

**AVAILABLE** — sbt-stryker4s 0.21.0 + `stryker4s.conf`. Read the capability
profile anyway; availability is a fact you read, not one you remember.

**RETARGET FIRST.** The `mutate` / `test-filter` lists are FIXED and currently
point at whichever spec used them last. Running unchanged scores that spec's
code and tells you nothing about this one. Set both to this spec's changed
files:
```bash
git diff --name-only <baseline-SHA>
```
**NAME A CELL:** `typesafe4s-client` compiles into six matrix cells, so "the
tests" is six different runs — say which (`sbt clientZio/stryker`).
`sbt core/stryker` is unambiguous. Mutating SHARED sources scores them through
one row; record which, because a survivor there is a gap on all five.

Then run it. Below threshold: identify surviving mutants, write a targeted test
per survivor citing `// spec: <spec-name> — Requirement: <title>`, update the
cross-reference table, re-run. **Max 2 iterations.**

**Read the survivors even when the score passes.** The first real run in this
repo passed at 80.65% while leaving `n >= 0` → `n > 0` alive in the retry-after
parser — no test pinned a service-named delay of zero. A passing score is not
an empty survivor list.

## Step 9 — Ring 4: formal verification (if available)

**APPLICABILITY IS A FACT — read the capability profile.** Ring 4 requires a
Stainless module that EXISTS IN THIS REPOSITORY. A version number in
`config.yaml` is not a module. **This project HAS one**: the leaf build at
`verified/` (Stainless 0.9.9.3, Scala 3.7.2, native Z3) — so Ring 4 is
available, and is `⏭️` only when THIS SPEC has no algorithmic invariant worth
mirroring, with the reason stated.

Do not report it as passed, as not-applicable-by-reflex, or as already handled.

When it applies:

1. Write or extend a mirror kernel under
   `verified/src/main/scala/typesafe4s/verified/`, translating the spec's
   `require`/`ensuring` contracts into PureScala. Production code is NOT
   verified directly — the frontend is Scala 3.7.2 and understands no carrier,
   no effects, no string interpolation. Use `BigInt`, not machine integers or a
   duration type: overflow conditions obscure the property you care about.
2. Run `scripts/ring4.sh` — **never bare `sbt ring4`**: Stainless reports a
   refuted condition as a warning and sbt exits 0. Setup, idioms and the hang
   checklist are in `verified/README.md`.
3. Add the Ring 2 **bridge property** running the production function and the
   kernel on the same generated input. **Without it the proof is about a model
   nobody runs, and Ring 4 is not evidence about shipped code** — spec-lint W6
   fails a spec that declares Formal Contracts without naming the bridge.

**What belongs here**: pure algorithmic invariants over values — the retry
schedule (`RetrySchedulerKernel`, already verified), the token-budget division.
NOT the carrier, the transport or a facade: those are Ring 5's.

**Ask the type system first.** The static API limits — Score levels 2–10,
Choice options ≤ 255, non-empty question sets — are already `inline if` +
`compiletime.error`, discharged by Ring 0 and a compile-negative test. A
compile error is stronger evidence than a proof about a model, and free to run.

If verification fails, fix the IMPLEMENTATION or the kernel, never the
contracts. Max 2 iterations.

## Step 10 — Ring 5: cross-backend parity (if applicable)

**REPURPOSED in typesafe4s** — this project emits no telemetry. Ring 5
verifies that five published artifacts built from ONE shared source tree
behave the same. Use the `openspec-ring5-parity` skill.

**APPLIES** when the spec touches shared client sources, a backend facade, the
`Transport` SPI, or the retry loop. A spec confined to `typesafe4s-core`
(pure, sans-IO, compiled once) does NOT need it — say so and move on.
Applicability is read from `git diff --name-only <baseline-SHA>`, not judged.

1. The behavioural suite is written **ONCE against `CIO`**, never per backend.
   A test that exists only in the ZIO row proves nothing about the other four.
2. **Run it on EVERY row and record each result separately**: zio, ce, ox,
   kyo, pekko. The `future` row is compile-only. "The tests pass" is not a
   verdict — five named green runs are. A row you did not run is a row you
   cannot report.
3. Verify the **compile-time API parity check** fires: remove one operation
   from one facade, observe the build fail, restore it. A parity check nobody
   has seen fail is an untested test.
4. **DECLARED DIVERGENCE**: where backends genuinely differ — `Future` and
   Pekko cannot cancel, so interruption is a documented no-op there — the
   difference goes in the spec and the scaladoc, asserted per row. Hiding it
   behind a uniform-looking API is the failure this ring exists to catch.
5. **CANCELLATION IS THE KNOWN TRAP**: kyo-compat's `fromCompletionStage` does
   not propagate cancellation. Any transport-touching spec asserts, per
   cancelling backend, that (a) interruption aborts the in-flight exchange,
   (b) interruption during a retry delay makes no further attempt, and
   (c) the retry budget is not consumed afterwards.
6. **WRITEBACK**: a divergence is a SPECIFICATION hole, not a test to loosen.
   Never author a scenario from observed behaviour. Stop the apply phase and
   open a finding in `openspec/ring5-findings/`.

Do NOT reach for otel4s or any observability dependency in this ring — there
is none in this project.

## Step 11 — Concept delta + inventory update

**CONCEPT DELTA** — verify the public surface changed EXACTLY as committed:
```
new surface = previous inventory + Concepts Introduced + approved extensions
```
Re-run the scanner and machine-diff:
```bash
openspec/schemas/verified-scala3/scanner/scan.sh . \
  --output openspec/changes/<CHANGE>/inventory-snapshots/<spec>-after.md
diff openspec/changes/<CHANGE>/inventory-snapshots/<spec>-{before,after}.md
```
**That diff IS the concept delta.** Compare it line by line against the spec's
Concepts Introduced table plus approved extensions. Eyeballing the code diff is
the fallback, not the method. Anything extra: update the spec and get approval,
or remove it. This is the scope-creep check.

**ARTIFACT CHECK**:
```bash
openspec/schemas/verified-scala3/scanner/spec-lint.sh --artifacts <change-dir>
```
Every code-shaped Artifact token in Proof Obligations must now resolve to a
tracked file (F9). A suite named in the table but never written means the
obligation was discharged some other way — fix the row to name something real
rather than leaving a plausible fiction in the ledger.

**BUILD-DEPENDENCY DELTA**: `git diff <baseline-SHA> -- build.sbt project/`.
Any NEW library is an unreviewed behavior source: named in the proposal/design,
or approved at the checkpoint, or removed. Record the delta or "none".

**REMOVAL AUDIT** — for every field or type REMOVED from a refactored concept:
```bash
openspec/schemas/verified-scala3/scanner/removal-audit.sh --suggest <baseline-SHA>
openspec/schemas/verified-scala3/scanner/removal-audit.sh <suspect-fqcn>...
```
Each orphan is deleted in this change or explicitly retained with rationale.
`RemoveUnused` does not flag public/opaque members — this audit is the only
thing that catches them.

**INVENTORY UPDATE** — use the `openspec-update-inventory` skill. Append the
verified NEW concepts to `openspec/concept-inventory.md` with provenance
`spec:<change>/<spec>`. Record ACTUAL source details — read the constraint the
code has, not the one the spec intended.

**REGISTRY UPDATE** — if this spec altered a concept's actions, state or
synchronizations, update `openspec/concepts/*.md` NOW. If a state component
changed, keep the fold row's `maps f1, f2, ...` declaration in step with the
fold's ACTUAL field list. Re-run `registry-check.sh`: it must pass. An
implementation whose concept files are stale is not complete.

## Step 12 — Commit, then CHECKPOINT ◄ STOP

Mark the checkbox in `implementation-progress.md` (`- [ ]` → `- [x]`) and in
`implementation-order.md`. Update the **Current position** block at the top
of `implementation-order.md` (next spec, last commit) and rewrite
`state.md` for the next spec — `spec:` advances, `step: awaiting human
validation`, rings back to `pending`, `prior-commit:` = this spec's commit,
`baseline:` cleared. The next session's startup is this rewrite done now,
not archaeology done then.

**COMMIT** — create this spec's commit now: all production, test and
change-artifact edits, message:
```
<change-name>: spec <n>/<m> — <spec-name>
```
The commit is the checkpoint's durable identity, the **Step 0 baseline of the
next spec**, and what makes every per-spec diff exact. Record the hash in
`implementation-progress.md`.

Then present and STOP:

```
## ✓ Spec N/M complete: <spec-name>

**Commit:** <sha> (baseline was <baseline-sha>)
**Gate tier:** two gates | combined | waiver
**Files changed:** <list>
**Rings:**
  Ring 0   Compile        ✅ / ❌
  Ring 1   Lint           ✅ / ❌
  Ring 1.5 Architecture   advisory (no rules installed) / ✅
  Ring 2   Property tests ✅ (N properties, M scenarios)
  Ring 8   Adversarial    ✅ / PARTIAL / FAIL
  Ring 3   Mutation       ✅ (score N%) / ⏭️ <reason>
  Ring 4   Formal         ✅ (N/N VCs valid, nativez3) / ⏭️ <reason>
  Ring 5   Telemetry      ✅ (N contracts) / ⏭️ <reason>
**Verification evidence:** <PASTED final lines of the ACTUAL compile and test
  output — success line, test counts. A ring claimed ✅ without pasted tool
  output is NOT verified and this checkpoint is invalid. Never present a
  checkpoint for code that has not been compiled.>
**Oracle polarity:** <N red→green confirmed, M green-by-design held>
**Adversarial review (Ring 8):** <verdict per requirement> (fresh-context: yes/no)
**Impact scan:** <sites found + resolution | n/a> (method: metals | grep)
**Proof obligations:** <all enforced | manually-reviewed ones listed>
**Concept delta (scanner diff):** <exactly as committed | deviations + resolution>
**Build-dependency delta:** <none | new deps + where approved>
**Removal audit:** <orphans + resolution | none>
**Concepts added to inventory:** <list or none>
**Concept registry files updated:** <list or none / n.a.>
**Ring 5 findings opened / resolved:** <list or none>
**Open questions for the human:** <pending items from state.md, verbatim — or none>
**Design decisions made:** <deviations from the spec, notable choices>

---
⏸️  Waiting for your review before starting spec <N+1>: <next-spec-name>
Reply "continue" (or describe changes you want) to proceed.
```

**STOP. Wait for explicit human approval.**

- DO NOT start Step 0 of the next spec until the human replies.
- DO NOT say "I'll go ahead and start the next one."
- DO NOT assume approval of a previous spec carries forward.

If the human requests changes: apply them, re-run only the affected rings, then
re-present this checkpoint.

## Error handling

| Situation | Action |
|-----------|--------|
| Working tree dirty at Step 0 | STOP — no baseline can be recorded |
| Concept missing from inventory | STOP, ask the human (Step 0b) |
| Registry check fails | STOP — fix the Implementation map first |
| MUST-CONFIRM item | STOP, ask for authoritative data — never synthesize |
| Typed contract will not compile | Fix it; it is not approvable uncompiled |
| A RED test passes pre-implementation | Broken oracle — fix before Gate 2 |
| Contract or oracle rejected at a gate | Edit, re-compile, re-present |
| Ring 0 fails after 3 iterations | STOP, present errors |
| Property falsified after 3 fixes | STOP, present the counterexample |
| Ring 8 returns FAIL | Fix, re-run affected rings; no checkpoint while open |
| Fresh context impossible for Ring 8 | Say so; checkpoint carries `fresh-context: no` |
| Concept delta shows extras | Update the spec (approval) or remove the concept |
| Two specs modify the same file | STOP, ask the human to resolve |

## Performance notes

- Rings are sequential — each depends on the previous passing.
- Ring 8 is cheap (reading) and runs before the expensive rings by design.
- Ring 3 (mutation) is the slowest; retargeting it per spec keeps it usable.
- A simple spec through gates + rings 0/1/2/8 typically takes 5–15 minutes.
