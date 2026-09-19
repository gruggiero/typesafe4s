---
name: openspec-apply-ring
description: >
  Run a single verification ring on the current Scala 3 code. Use when you
  want to re-run a specific ring after making changes, or to check the
  status of a particular verification level without running the full pipeline.
globs:
  - "src/main/scala/**/*.scala"
  - "src/test/scala/**/*.scala"
  - "build.sbt"
  - ".scalafix.conf"
metadata:
  generatedBy: verified-scala3-schema/5.0.0
---

# Apply Ring Skill

## Usage

`/opsx:ring <N>` where N is: `0`, `1`, `1.5`, `2`, `3`, `4`, or `5`

## Ring Reference

### Ring 0 — Compilation

```bash
sbt compile
```

Checks: type safety, exhaustive matching, unused values, discarded effects.

Depends on: `build.sbt` scalacOptions including `-Werror`, `-Wunused:all`,
`-Wvalue-discard`.

**Fix strategy**: Read compiler error, fix the exact line. Common LLM mistakes:
returning `String` where `AccountId` expected (use smart constructor),
discarding an `IO[Unit]` (use `_ <-` in for-comprehension or `*>`),
non-exhaustive match (add missing cases).

### Ring 1 — Lint

```bash
sbt "scalafix --check"
```

Checks: `DisableSyntax` (no var/null/throw/return/while), `RemoveUnused`.

Depends on: `.scalafix.conf` configuration, `semanticdbEnabled := true` in build.

**Fix strategy**: Each Scalafix violation includes a rule name and location.
`DisableSyntax.noVars` → replace `var` with `Ref[IO, A]`.
`DisableSyntax.noThrows` → replace `throw` with `Either.Left` or `IO.raiseError`.

### Ring 1.5 — Architecture

```bash
sbt "scalafix --check --rules=LayerDependencies --rules=SealedDomainTypes --rules=EffectDiscipline --rules=RawPrimitivesInDomain --rules=ErrorHandlingDiscipline"
```

Checks: package layer boundaries, sealed domain types, effect discipline
(F[_] returns, no Future), no raw primitives in domain APIs, error handling
discipline (no Try, sealed error types).

Depends on: `io.gruggiero:scalafix-arch-rules` published locally.

**Setup** (one-time):
```bash
cd scalafix-arch-rules && sbt publishLocal
```

Then in your `build.sbt`:
```scala
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
ThisBuild / scalafixDependencies += "io.gruggiero" %% "scalafix-arch-rules" % "0.1.0-SNAPSHOT"
```

Once configured, all 5 architecture rules run automatically via `sbt "scalafix --check"`.

**Fix strategy**: Architecture violations usually require structural changes,
not one-line fixes. `LayerDependencies` → move the type to the correct package
or add an abstraction. `EffectDiscipline` → wrap return type in `F[_]`.

### Ring 2 — Property-Based Tests

```bash
sbt test
```

Checks: properties (forAll invariants) and acceptance scenarios, in the
framework `openspec/capability-profile.md` records — MUnit + munit-scalacheck
in this project. Never generate tests for a framework the profile does not
record.

Depends on: test files exist with properties.

**Fix strategy**: Read the counterexample. The property is the SPEC — fix the
implementation, not the test. Feed the exact counterexample values to understand
why the property was falsified.

### Ring 3 — Mutation Testing

```bash
sbt core/stryker          # or clientZio/stryker, clientCe/stryker, …
```

**AVAILABLE** — sbt-stryker4s 0.21.0 with `stryker4s.conf` at the repo root.

**RETARGET FIRST.** `stryker4s.conf` carries FIXED `mutate` and `test-filter`
lists. Running it unchanged scores the PREVIOUS spec's code and tells you
nothing about this one. Set both to this spec's changed files before running:

```bash
git diff --name-only <baseline-SHA>
```

**NAME A CELL.** `typesafe4s-client` compiles into six matrix cells, so "the
tests" is six different test runs — a mutation run must say which
(`clientZio/stryker`). `core/stryker` has a single cell and is unambiguous;
core is also pure and sans-IO, which is where mutation earns most and costs
least. Mutating SHARED client sources scores them through exactly one row:
that is fine, the sources are identical per row, but RECORD which row ran,
because a survivor found on one row is a gap on all five.

Checks: test-suite strength. Surviving mutants mark code paths the tests do
not actually constrain.

**Fix strategy**: For each survivor, write a test that covers the mutated path
specifically, citing `// spec: <spec-name> — Requirement: <title>`. Example
from this repo's first real run: `n >= 0` → `n > 0` in the retry-after parser
survived, meaning no test pinned a service-named delay of ZERO — a delay the
service can legitimately name. Max 2 iterations, then re-run.

### Ring 4 — Formal Verification

```bash
scripts/ring4.sh
```

**AVAILABLE** — Stainless 0.9.9.3 in the leaf `verified/` build (Scala 3.7.2).
One-time setup per checkout: `scripts/setup-stainless.sh`.

**NEVER run bare `sbt ring4`.** Stainless reports a refuted verification
condition as a WARNING and sbt still exits 0. The script reads the summary and
turns invalid or unknown conditions into a non-zero exit, and enforces a
wall-clock timeout because Stainless 0.9.9.3 has no working per-condition
timeout — a hard condition hangs forever.

The script prints the solver. `nativez3` is expected; `smt-z3` means the
ScalaZ3 jar is missing (untracked, Linux x86_64) and needs a `z3` binary on
PATH.

**A KERNEL WITHOUT A BRIDGE PROVES NOTHING.** The kernel is a MODEL, in a leaf
module that cannot even reference production code. What makes it evidence
about this SDK is the Ring 2 **bridge property** running the real function and
the kernel on the same generated input. If a spec declares Formal Contracts, an
obligation MUST name that bridge (spec-lint W6 checks this).

**What belongs here**: pure, algorithmic invariants over values — the retry
schedule, the token-budget division. NOT anything touching the carrier, the
transport or a facade; that is Ring 5's business.

**Prefer the type system first.** The static API limits (Score levels 2–10,
Choice options ≤ 255, non-empty question sets) are already `inline if` +
`compiletime.error`, discharged by Ring 0 and a compile-negative test. A
compile error is stronger evidence than a proof about a model, and free to run.

**Fix strategy**: an invalid condition means the implementation or the kernel
is wrong — fix one of those, NEVER weaken the contract. A contract relaxed
until it passes says nothing. Max 2 iterations.

### Ring 5 — Cross-Backend Parity

REPURPOSED in typesafe4s: this project emits no telemetry. Ring 5 verifies
that five published artifacts built from one shared source tree behave the
same. Use the `openspec-ring5-parity` skill.

```bash
sbt integrationTestsZio/test
sbt integrationTestsCe/test
sbt integrationTestsOx/test
sbt integrationTestsKyo/test
sbt integrationTestsPekko/test
```

Checks: one suite written against `CIO`, executed on EVERY row and recorded
per row; the compile-time API parity check; declared divergences (`Future` and
Pekko cannot cancel) asserted rather than hidden.

Applies when the spec touches shared client sources, a facade, the transport
or the retry loop. A core-only spec does not need it — say so.

**Fix strategy**: A divergence is a SPECIFICATION hole, not a test to loosen.
Never author a scenario from observed behaviour — open a finding under
`openspec/ring5-findings/` and stop at the checkpoint.

> "The tests pass" is not a verdict. Five named green runs are.

## Output Format

After running any ring, report:

```
Ring <N>: <name> — <PASSED|FAILED>
  Duration: <time>
  Iterations: <count> (if retries were needed)
  Details: <summary of errors or "clean">
```
