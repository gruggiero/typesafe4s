# Capability Profile

<!-- PROJECT-SCOPED LIVING DOCUMENT. Each change's capability-check artifact
     verifies and refreshes it. DETECTED by inspecting build.sbt,
     project/plugins.sbt, project/build.properties and by running sbt — never
     assumed. If this file disagrees with openspec/config.yaml, THIS FILE WINS
     and config.yaml gets fixed. -->

**Detected:** 2026-09-17 · **State:** build stub only — no SDK behaviour implemented.

> **RING NUMBERING IS THIS PROJECT'S:** 0 compile · 1 lint · 1.5 architecture ·
> 2 property tests · 3 mutation · 4 formal · **5 CROSS-BACKEND PARITY** · 8 adversarial.
> Ring 5 is repurposed from telemetry — this project emits none. See `openspec/config.yaml`.

## Build & Language

| Item | Detected Value | Evidence |
|------|---------------|----------|
| Scala version | 3.9.0 (single version; no cross-build) | `sbt show core/scalaVersion` |
| sbt version | 1.13.0 | project/build.properties |
| JDK (toolchain) | **26.0.1 (Homebrew OpenJDK)** — the only JDK ≥ 21 on this machine | `java -version`, sbt banner |
| JDK (target) | 21, via `-release 21` | build.sbt `commonSettings` |
| Fatal warnings | **`-Werror` IS ACTIVE.** Any warning fails Ring 0. | `sbt show clientZio/Compile/scalacOptions` |
| scalacOptions | `-deprecation`, `-no-indent`, `-release 21`, `-Wunused:imports,params,privates,implicits,explicits`, `-Wvalue-discard`, `-Werror`, `-Xkind-projector` | same |
| Dependency management | Inline `val`s at the top of build.sbt. **No** `project/Versions.scala`, **no** `project/Dependencies.scala`. | build.sbt |
| semanticdb | **DISABLED** (`false`) — no semantic Scalafix rules are possible today | `sbt show core/semanticdbEnabled` |
| Build plugins | kyo-compat-plugin 1.0.0-RC6, sbt-projectmatrix 0.11.0, sbt-buildinfo 0.13.1, sbt-scalafmt 2.6.2, sbt-ci-release 1.12.1 | project/plugins.sbt |

### Modules and matrix rows

`sbt projects` reports **16 projects**. The matrix generates one cell per backend axis.

| Project id | Artifact (`moduleName`) | Published? | Purpose |
|---|---|---|---|
| `core` | `typesafe4s-core` | yes | Pure, sans-IO. **Zero runtime dependencies.** |
| `compatCe` | `typesafe4s-compat-ce` | yes | Vendored `kyo.compat` Cats Effect binding |
| `clientZio` | `typesafe4s-client-zio` | yes | |
| `clientCe` | `typesafe4s-client-ce` | yes | |
| `clientOx` | `typesafe4s-client-ox` | yes | |
| `clientKyo` | `typesafe4s-client-kyo` | yes | |
| `clientPekko` | `typesafe4s-client-pekko` | yes | |
| `clientFuture` | `typesafe4s-client-future` | **NO** (`publish/skip := true`) | Compile-only anchor row |
| `integrationTests{Zio,Ce,Ox,Kyo,Pekko,Future}` | — | **NO** | Ring 5 parity suite, one per row |
| `ceConformance{Ce,Future}` | — | **NO** | Upstream conformance suite vs the vendored binding |
| `root` | `typesafe4s` | **NO** | Aggregator |

**Verified publication contract:** `publish/skip` is `true` for `clientFuture`, every `integrationTests*` row, and `root`; `false` for `core`, `compatCe` and the five published client rows.

## Libraries

| Concern | Detected | Version | Notes |
|---------|----------|---------|-------|
| Effect carrier | kyo-compat | 1.0.0-RC6 | **PRE-1.0, PINNED.** `CIO`/`CStream` opaque types, all `inline def`. |
| Effect (zio row) | zio, zio-streams | 2.1.26 | + `io.getkyo:kyo-compat-zio` |
| Effect (ce row) | cats-effect, fs2-core | 3.7.1 / 3.14.0 | **No `kyo-compat-ce` artifact** — bound locally to `compatCe` via `bindLocally`. Verified: the row's `libraryDependencies` contain no `io.getkyo` entry. |
| Effect (ox row) | ox core | 1.0.6 | + `io.getkyo:kyo-compat-ox` |
| Effect (kyo row) | — | — | `io.getkyo:kyo-compat-kyo` only |
| Effect (pekko row) | pekko-stream, pekko-actor-typed | 1.7.0 | + **`io.getkyo:kyo-compat-future`** (Pekko rides on `Future`) |
| HTTP | **none** | — | `java.net.http.HttpClient` (JDK) is the planned transport. No http4s/sttp/tapir. |
| JSON | **none** | — | Core will ship its own AST. No circe/zio-json/jsoniter/upickle on any classpath. |
| Refined types | **none** | — | No Iron, no refined. Plain Scala 3 opaque types. |
| IDL / codegen | **none** | — | No Smithy. sbt-buildinfo emits `version` into `typesafe4s.client` only. |
| Telemetry | **none** | — | No otel4s, no monitor library, no observability module. **This is why Ring 5 is parity, not telemetry.** |
| Persistence / messaging | **none** | — | No database, no Kafka. This is a library. |
| Logging | **none** | — | No slf4j/logback on any classpath. |

## Testing

| Concern | Detected | Consequence |
|---------|----------|-------------|
| Test framework | **munit 1.3.6** (Test scope, every module) | Generated tests use `munit.FunSuite`. **NOT ScalaTest. NOT weaver. NOT Hedgehog.** |
| Property testing | **munit-scalacheck 1.3.1** → scalacheck **1.20.0** transitively | Properties extend `munit.ScalaCheckSuite` with `property("…") { forAll { … } }`. **⚠️ PRESENT BUT UNEXERCISED** — no property has been written yet, so this pairing has never actually run in this repository. First property written must be treated as also validating the harness. |
| Coverage assertion | **NONE.** ScalaCheck `classify`/`collect` are informational and cannot fail a build. | A conditioned property whose antecedent is rarely generated passes **VACUOUSLY** with nobody noticing. Every property must state how interesting cases are reached **by construction**. |
| Deterministic concurrency test kit | **PRESENT — a project-built VirtualTime clock** (`ManualClock`), written once against the carrier, in `typesafe4s-client/shared` test sources | Timing requirements go through a `Clock` seam in the shared runtime, so they are deterministic on **every** row. Verified 2026-09-17: 9 tests green on all five published rows. **spec-lint reads this row.** |
| Async test support | munit runs `Future`-returning tests via `munitExecutionContext` | The carrier's `unsafeRun` returns `scala.concurrent.Future` on every backend. **DIVERGENCE: the Ox binding's `unsafeRun` takes an `ExecutionContext`; the other four do not.** A shared suite must supply one and mark it `@unused`, or the rows that ignore it fail `-Werror -Wunused`. |
| Cross-backend suite | `integration-tests/shared` compiled into all 6 rows | One suite, six compilations. |
| Conformance suite | Bundled in `kyo-compat-plugin`, run by `ceConformanceCe` | **346 tests, 3 pending, 0 failures** observed 2026-09-17. |
| Test count | (3 carrier + 9 clock) parity tests × 5 executed rows + 346 conformance | No unit tests yet in `core` or `client`. |
| Forking | `Test / fork := true`; `testForkedParallel := true` on core and client | |

> **On that row:** the VirtualTime clock is **project-built, not a third-party kit.** Every ecosystem kit is
> ABSENT here — Cats Effect's `TestControl` is ABSENT, ZIO's `TestClock` is ABSENT, and Ox and Kyo ship no
> equivalent at all. That asymmetry is exactly why a carrier-level clock was built instead: an
> ecosystem kit would have forced timing tests to be written per row, breaking the one-suite-for-every-row rule
> Ring 5 rests on, and would have left two rows with no deterministic option.

## Static Analysis

| Tool | Active Rules | Evidence |
|------|-------------|----------|
| scalafmt | **ACTIVE.** scala3 dialect, `allowSignificantIndentation = false`, maxColumn 150, `align.preset = most`, import grouping with a `typesafe4s.*` group | .scalafmt.conf; `sbt check` clean |
| Scalafix | **NOT INSTALLED.** No plugin, no `.scalafix.conf`. semanticdb is off, so semantic rules are not even possible without a build change. | project/plugins.sbt, filesystem |
| WartRemover | **NOT INSTALLED.** No plugin. | project/plugins.sbt |
| Architecture rules | **NOT INSTALLED.** Ring 1.5 has no mechanism. | — |
| stryker4s | **INSTALLED.** sbt-stryker4s 0.21.0 + `stryker4s.conf`. Verified 2026-09-17 against the real spec-1 target: **80.65% total, 83.33% of covered code — PASS** (break 75). Its `mutate` / `test-filter` lists are **FIXED and must be retargeted per spec**; they currently point at error-model. | project/plugins.sbt, stryker4s.conf |
| Stainless | **INSTALLED.** 0.9.9.3 via the leaf `verified/` build (Scala 3.7.2, native Z3). Verified 2026-09-17: `scripts/ring4.sh` gave **13/13 conditions valid on `nativez3`.** | verified/build.sbt, scripts/ring4.sh |

> Ring 1 is therefore **scalafmt + `-Werror`** and nothing else. `-Werror` with `-Wunused` is doing real work here; do not describe Ring 1 as absent.

## Code Intelligence

| Item | Detected | Evidence |
|------|----------|----------|
| Metals MCP endpoint | **ABSENT** — no `.metals/mcp.url`. Startable via `scanner/metals-start.sh` if wanted. | filesystem |
| External-dep API lookup | **cellar 0.1.0-M9** (native-image, linux-x86_64) | `cellar --version` |
| scala-cli | **1.16.0** — required by `scanner/scan.sh` (concept scanner) | `scala-cli version` |
| Fallback | `git grep` — the only tool until Metals is started | — |

## Compile & Test Commands

Only these are known to work; typed contracts and tests are trustworthy only when compiled with them.

| Purpose | Command |
|---------|---------|
| Compile all | `sbt compile` |
| Compile one row | `sbt clientZio/compile` (also `clientCe`, `clientOx`, `clientKyo`, `clientPekko`, `clientFuture`) |
| **Test compile (typed contracts)** | `sbt <project>/Test/compile` — e.g. `sbt core/Test/compile`, `sbt clientZio/Test/compile` |
| Unit tests, all rows + conformance | `sbt testUnit` |
| Conformance vs vendored binding | `sbt conformanceCe` |
| **Ring 5 parity, all rows** | `sbt parityAll` |
| Ring 5 parity, one row | `sbt parityZio` / `parityCe` / `parityOx` / `parityKyo` / `parityPekko` |
| Anchor row (compile-only) | `sbt integrationTestsFuture/Test/compile` |
| Single test | `sbt "integrationTestsZio/testOnly typesafe4s.integration.CarrierParitySuite"` |
| Format | `sbt fmt` · check only: `sbt check` |
| **Ring 3 mutation** | retarget `stryker4s.conf`, then `sbt core/stryker` (or `clientZio/stryker`, …) |
| **Ring 4 formal** | `scripts/ring4.sh` — **never** bare `sbt ring4` |
| Ring 4 setup (once) | `scripts/setup-stainless.sh` |
| Concept scan | `bash openspec/schemas/verified-scala3/scanner/scan.sh .` |
| Spec lint | `bash openspec/schemas/verified-scala3/scanner/spec-lint.sh openspec/changes/<change>` |

## Typed Contract Placement

Files under `openspec/changes/…` are **NOT compiled by sbt** — never place a typed contract there.

- Core contracts: `typesafe4s-core/src/test/scala/typesafe4s/typecontract/<SpecName>TypeContract.scala` → `sbt core/Test/compile`
- Shared client contracts: `typesafe4s-client/shared/src/test/scala/typesafe4s/client/typecontract/…` → **compiles into every row**; verify with at least `sbt clientZio/Test/compile` and `sbt clientCe/Test/compile`
- Backend facade contracts: `typesafe4s-client/<row>/src/test/scala/…` → that row only
- Compile-negative obligations use munit's `compileErrors("…")` (munit 1.x). **Not** ScalaTest's `assertDoesNotCompile`, which is not on the classpath.

## Domain Purity Rules (feeds Ring 1 / Ring 5)

No enforcement plugin is active, so these are **manual** until Scalafix arrives.

| Layer | Must NOT reference | May reference |
|-------|--------------------|---------------|
| `typesafe4s-core` | anything outside the Scala stdlib — **zero runtime deps is a contract** | stdlib only |
| `typesafe4s-client/shared` | **any backend type** — no ZIO, cats-effect, Ox, Kyo or Pekko import, ever | `kyo.compat.*`, `typesafe4s-core`, JDK |
| `typesafe4s-client/<row>` | other backends | its own backend, `kyo.compat.*`, shared client |
| `integration-tests/shared` | any backend type | `kyo.compat.*`, client, core, munit |
| `typesafe4s-compat-ce` | typesafe4s code — it is vendored upstream code | cats-effect, fs2 |

## Ring Availability Summary

| Ring | Available? | Mechanism / impact if not |
|------|-----------|---------------------------|
| **0 Compile** | ✅ | `sbt compile` / `sbt <p>/Test/compile`, **`-Werror` active** |
| **1 Lint** | ✅ *(partial)* | scalafmt + `-Werror -Wunused`. No Scalafix, no WartRemover — no `var`/`null`/`throw`/`asInstanceOf` ban is mechanically enforced. Those remain review-only until a plugin is added. |
| **1.5 Architecture** | ❌ | No rules installed, semanticdb off. The purity table above is manual. Skip with stated correctness impact. |
| **2 Property tests** | ✅ *(unexercised)* | munit-scalacheck 1.3.1 / scalacheck 1.20.0. **No property written yet** — first one validates the harness too. No coverage assertion; state constructive reachability. Timing-dependent properties may use the VirtualTime clock. |
| **3 Mutation** | ✅ | `sbt <cell>/stryker`. **Retarget `stryker4s.conf` per spec** or the score describes the previous spec's code. On the client matrix a cell must be named (`clientZio/stryker`, …) because "the tests" is six different runs; `core/stryker` is unambiguous. |
| **4 Formal** | ✅ | `scripts/ring4.sh` (never bare `sbt ring4` — Stainless reports a refuted condition as a warning and sbt exits 0). Leaf build at `verified/`, Scala 3.7.2. **A kernel needs a Ring 2 bridge property** or it proves nothing about shipped code. Still prefer the type system first: the static API limits are already `inline if` + `compiletime.error`. |
| **5 Cross-backend parity** | ✅ | `sbt parityAll`. One suite against `CIO`, executed on 5 published rows; `future` compile-only. Compile-time API parity check: `typesafe4s.client.ApiSurface[F]` — every facade `object TypesafeClient` implements it, so a forgotten operation fails that row's compile (closed 2026-09-18, effect-portability). |
| **8 Adversarial review** | ✅ | Always available; fresh-context subagent, mandatory before Rings 3/4/5. |

## Known gaps to close

1. ~~No deterministic time/concurrency test kit on any row.~~ **CLOSED 2026-09-17** — a virtual `ManualClock` written once against the carrier, green on all five rows. See "Deterministic concurrency test kit" above.
2. ~~**No compile-time API parity check** (task 10.5) — Ring 5's cheapest guarantee is still missing.~~ **CLOSED 2026-09-18** — `typesafe4s.client.ApiSurface[F]`; every facade object implements it; removal-and-restore demonstrated (ce facade `of(config, transport)` → compile error naming the member).
3. **Ring 3 survivors in error-model, above threshold but real.** The run passes at 80.65%, and three survivors are worth a decision rather than a shrug:
   - `n >= 0` → `n > 0` in the retry-after parser survives: **no test pins a service-named delay of zero**, which is a delay the service can legitimately name (retry at once).
   - the companion bound `n <= maxInUnit` likewise survives at its boundary.
   - `value.isBlank` → `true` survives: a blank request-id header is unpinned.
   These are in a spec that is already checkpointed, so they are recorded here rather than fixed silently.
4. **Ring 4 has one kernel and no bridge property yet.** `RetrySchedulerKernel` verifies, but until `retry-policy` is implemented and bridged, that proof is about a model nobody runs.
5. **ScalaZ3 is a local, untracked artifact.** Without `verified/unmanaged/scalaz3_3-4.13.4.jar`, Ring 4 falls back to `smt-z3` and needs a `z3` binary on PATH. Linux x86_64 only.
3. **munit-scalacheck unexercised** — the pairing has never run.
4. **JDK 26 toolchain, JDK 21 target.** CI should pin a real JDK 21 so the toolchain is tested, not only the target level.
5. **No Scalafix/WartRemover** — the coding conventions in `openspec/config.yaml` (no `var`, no `null`, no `throw`, no `asInstanceOf`) are currently unenforced claims.
