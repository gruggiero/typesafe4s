---
name: openspec-ring5-parity
description: >
  Generate and discharge Ring 5 CROSS-BACKEND PARITY evidence for a spec:
  one behavioural suite written against the kyo-compat carrier, executed on
  every published backend row, the compile-time API parity check, and the
  declared-divergence table for semantics that genuinely differ between
  ecosystems. Runs during Step 10 of /opsx:next-spec (schema v5, typesafe4s).
globs:
  - "openspec/changes/*/specs/**/*.md"
  - "**/src/main/scala/**/*.scala"
  - "**/src/test/scala/**/*.scala"
metadata:
  generatedBy: verified-scala3-schema/5.0.0
---

# Ring 5 — Cross-Backend Parity

## Why this ring exists here

Upstream, Ring 5 verifies telemetry. **typesafe4s emits no telemetry**, so
that ring would be dead weight. This slot verifies the risk this project
actually carries.

One shared source tree is compiled into five published artifacts —
`typesafe4s-client-{zio,ce,ox,kyo,pekko}`. The carrier type `CIO[A]` is an
`opaque type` resolving to a different concrete effect per artifact, and every
operation is an `inline def`. That means **the same source can compile to five
genuinely different runtime behaviours**, and the type system will not tell
you. Only an executed suite will.

This ring is the difference between *assuming* parity and *having* it.

## Applicability — read this before doing any work

Ring 5 **APPLIES** when the spec touches:

- shared client sources (anything under the client's `shared/` tree),
- a backend facade,
- the `Transport` SPI or the retry loop,
- anything a user could observe through more than one backend artifact.

Ring 5 **DOES NOT APPLY** to a spec confined to `typesafe4s-core` — core is
pure, sans-IO, and compiled once. Say so explicitly and move on. Claiming this
ring for a core-only spec is noise; skipping it for a shared-source spec is a
hole.

**APPLICABILITY IS A FACT, NOT A JUDGEMENT.** Read which files the spec
changes (`git diff --name-only <baseline-SHA>`) and decide from that.

## Step 1 — the suite is written ONCE, against the carrier

```
integration-tests/shared/src/test/scala/...   ← the suite lives here
```

Write behavioural tests against `CIO`, never against `IO`, `ZIO`, `Task`, or
an `Ox` scope. The build recompiles this one suite into every backend row.

**A test that exists only in the ZIO row proves nothing about the other four.**
If you find yourself writing a backend-specific test, you are either:

- testing a *declared divergence* (legitimate — see Step 4), or
- working around a leak of a backend type into shared code (a Ring 0/1 bug,
  fix that instead).

## Step 2 — run it on EVERY row, and record each result

```bash
sbt integrationTestsZio/test
sbt integrationTestsCe/test
sbt integrationTestsOx/test
sbt integrationTestsKyo/test
sbt integrationTestsPekko/test
```

Record the rows in a table, each with its own observed result:

| Row | Command | Result | Notes |
|-----|---------|--------|-------|
| zio | `integrationTestsZio/test` | | |
| ce | `integrationTestsCe/test` | | |
| ox | `integrationTestsOx/test` | | |
| kyo | `integrationTestsKyo/test` | | |
| pekko | `integrationTestsPekko/test` | | |

> **"The tests pass" is not a Ring 5 verdict. Five named green runs are.**
> A row you did not run is a row you cannot report. Do not write "same as
> above", "N/A", or "unchanged" — the whole point of the ring is that rows
> can differ while the source does not.

The `future` row is a compile-only anchor: it is never published, so it is
compiled, not run. Record that as `Test/compile`, not as a passing suite.

## Step 3 — the compile-time API parity check

An operation added to one facade and forgotten on another must **fail the
build**, not a review. Verify the parity check exists and actually fires:

1. Confirm the check compiles as part of the client module.
2. Prove it is not vacuous: temporarily remove one operation from one facade,
   observe the build fail, restore it. Record that you did this.

A parity check nobody has ever seen fail is an untested test.

## Step 4 — declared divergence

Some backends genuinely differ. **`Future` and Pekko cannot cancel**, so
interruption is a documented no-op there.

Where behaviour differs, it is **DECLARED, NOT DISCOVERED**:

- the difference belongs in the spec,
- and in the published API documentation,
- and in a test that asserts the *documented* behaviour per row.

| Behaviour | zio | ce | ox | kyo | pekko | Declared where |
|-----------|-----|----|----|-----|-------|----------------|
| interruption aborts in-flight request | yes | yes | yes | yes | **no** | spec + scaladoc |

Hiding a divergence behind a uniform-looking API is exactly the failure this
ring exists to catch. A uniform signature over non-uniform semantics is worse
than an honest difference, because it silently breaks at runtime in only one
ecosystem.

## Step 5 — cancellation is the known trap

`kyo-compat`'s own source says of `fromCompletionStage`:

> *"cancellation does NOT propagate back"*

A naive lift leaves the HTTP exchange running after the caller is interrupted.
Any spec touching the transport **MUST** assert, per cancelling backend:

1. interrupting the caller aborts the in-flight exchange,
2. interrupting during a retry delay makes no further attempt,
3. the remaining retry budget is not consumed after interruption.

These are three separate assertions. A test that only checks (1) leaves the
retry loop burning budget for a caller that walked away.

## Step 6 — writeback

A divergence found here is a **SPECIFICATION hole, not a test to loosen**.

- **NEVER** author a scenario from observed behaviour. "ZIO does X, so the
  spec now says X" inverts the whole workflow.
- Stop the apply phase.
- Open a finding under `openspec/ring5-findings/` naming the rows that
  disagreed, the observed behaviours, and the requirement that is now
  underspecified.
- Take it to the human checkpoint.

## Prohibited in this ring

Do **not** reach for otel4s, a monitor library, or any observability
dependency. There is none in this project, and introducing one is a
build-dependency delta that needs approval. If you believe this ring needs a
new dependency, you have misread it — it needs an executed suite, not a
library.

## Output format

```
RING 5 — CROSS-BACKEND PARITY
Applies: yes/no  (reason, from the changed-file list)

Rows executed:
  zio   <command>  <result>
  ce    <command>  <result>
  ox    <command>  <result>
  kyo   <command>  <result>
  pekko <command>  <result>
  future <command> compile-only

API parity check: present / fires-when-broken (proved: yes/no)
Declared divergences: <table, or "none">
Cancellation assertions: <per row>
Findings opened: <path, or "none">

VERDICT: PASS / FAIL  (a row not run is not a PASS)
```
