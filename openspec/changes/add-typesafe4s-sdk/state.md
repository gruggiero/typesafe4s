# State — add-typesafe4s-sdk

spec: 10/10 release-readiness — credential-gated live suite per row, live wire-fact
  reconciliation (task 12.3), per-row examples, README, CI matrix + snapshot publishing
step: committed e11c401 (spec 10/10 — release-readiness) — AWAITING HUMAN CHECKPOINT.
  This was the final spec in the change; no spec 11 exists.

  LIVE RECONCILIATION RUN 2026-09-19 (task 12.3, credentialed):
  [live-reconciliation] LiveReconciliation(
    modelsDecoded=Left(the service's answer did not fit the agreed shape at 'data':
      no 'data' member [request id: req_01a0ba245efc725f8eefb910356e7d0c]),
    modelsRawBody={"models":[{"name":"jev-latest","description":"The latest
      iteration of TypeSafe's System One Model: Jev","release_date":
      "2026-09-10T18:38:01.391457+00:00"},{"name":"jev-preview","description":
      "A preview version of `jev-latest`: should be better in most ways",
      "release_date":"2026-09-10T18:39:06.057655+00:00"}]},
    retryAdviceHeaders=List(), statusesSeen=List(200),
    understatedEstimates=List(small: estimate 74 < reported inputTokens 272,
      unicode: estimate 86 < reported inputTokens 286),
    unreportedAccounting=List())
  RESOLVED: models shape CORRECTED to {"models":[{name,description,release_date}]}
    (Models.decodeResponse + all fixtures); estimate CORRECTED — service counts a
    fixed ~250-token scaffold invisible in the request → estimate is now
    ServiceOverhead(512) + ceil(bytes/3).
  NOT OBSERVED: no 429/529 seen (statusesSeen=List(200)) — retry-after header names
    and Overloaded semantics remain unconfirmed; the suite cannot force them.
  PENDING: re-run with a key after corrections — record must show
    modelsDecoded=Right(n) and understatedEstimates=List().
  NOTE: one timing flake observed — QuestionBatchingParitySuite on integrationTestsCe failed once
  under the full parallel run ("abandoned in-flight exchanges were not aborted"), passed on
  isolated re-run AND on the parityAll re-run. Unrelated to this diff; watch it.
baseline: ee32763  # spec 9/10 json-adapters checkpoint commit (clean tree)
prior-commit: ee32763
gate-tier: one gate (no new SDK types, no new error paths — release machinery)
gates: single combined gate APPROVED (contract + oracle + design decisions; post-gate contract
  amendment from Ring-8 F1 flagged at checkpoint: LiveGate.resolve → credentialed+config)
rings: R0 ✅ (all 6 example cells + live suites compile under -Werror) · R1 ✅ (sbt check green —
  scalafmt + all check tasks; lint-diff.sh's scalafixAll is a script/profile mismatch: scalafix
  NOT INSTALLED per capability-profile) · R1.5 advisory (no rules installed) · R2 ✅ (oracle
  green: gate test passes, 2 live tests skip without credential; tier-4 assertions in
  checkReleaseReadiness + extended checkPublicationContract run green in sbt check) ·
  R8 ✅ (fresh-context review: 1 PASS / 4 PARTIAL→fixed / 0 FAIL — F1 gate collapse, F2 vacuous
  margin check, F3 ci.yml gitignored, F4/F5 vacuous assertions; contract amended:
  LiveGate.resolve → credentialed+config) · R3 ⏭️ none declared · R4 ⏭️ none declared ·
  R5 ✅ (parityAll POST-FIX: 5 rows × 35 tests, 33 pass + 2 skipped each — live suite
  runs/skips identically on every published row; examples compile per row ×6 cells)
registry: ok (--only-change add-typesafe4s-sdk; 85 tokens — stale credential.md/evaluation.md
  implementation maps repaired to realized code: TypesafeConfig.resolve, ApiKey render/redact,
  Client.listModels→Models.decodeResponse, Usage/RequestId)
metals: up http://localhost:8396/mcp (verified — serverInfo.name == typesafe4s-metals)
scanner-gaps: carried from specs 1-9 (danger-scan/git-diff miss UNTRACKED files — hand new-file
  list to Ring 8 reviewer; impact-scan grep misses new types; compileErrors staleness after impl
  changes — clean rebuild needed; message assertions can pass vacuously on source echo)
deviations: none yet for spec 10
open-questions:
  - MUST-CONFIRM (pending, task 12.3, THIS spec owns): retry-after header names
    `Retry-After`/`retry-after-ms` — reconciled only if a live key is provided; else stays pending.
  - MUST-CONFIRM (pending, task 12.3, THIS spec owns): 529 Overloaded semantics — same gating.
  - MUST-CONFIRM (pending, task 12.3, THIS spec owns): `GET /v1/models` body shape — same gating.
  - MUST-CONFIRM (pending, task 12.3, THIS spec owns): estimator margin vs real service
    accounting — no published tokenizer; conservative margin is a live-key check.
  - spec-6 residual (Ring 8 obs 3): `QuestionSet.apply` admits a forged `Right` past `validated`'s
    runtime limits — move `limitViolation` into QuestionSet's smart constructors (question-model
    domain; revisit at final review).
  - NEW: examples placement — spec anchors allow `examples/` tree or modules; no examples exist yet.
  - NEW: CI platform assumed GitHub Actions per spec anchor; no .github dir exists yet.
next: Step 0b — concept check (Credential/Evaluation/Token Budget), registry gate, then Step 1
  typed contract.

## Capability commands
compile: sbt compile · test-compile: sbt core/Test/compile + per-row client Test/compile
  + adapter modules: sbt jsonCirce/test jsonZio/test jsonJsoniter/test jsonUpickle/test
test: sbt core/test + sbt testUnit (per-row client tests) · parity: sbt parityAll (5 rows)
lint: sbt check (scalafmt + checkCoreDependencies + checkPublicationContract + checkSharedSources
  + checkAdapterDependencies) + -Werror via compile · format: sbt fmt · lint-diff: scanner/lint-diff.sh <baseline>
mutation: ⏭️ not declared for this spec · formal: ⏭️ not declared for this spec
test-framework: munit 1.3.6 · test-kits: munit-scalacheck 1.3.1, ManualClock (client test sources)
compile-negative: munit compileErrors (NOT ScalaTest assertDoesNotCompile)
