## 1. Build skeleton and effect portability

- [x] 1.1 Create the sbt build: Scala 3.9.0, JDK 21 target, `-Werror`, the Sage compiler-option set, scalafmt, and `sbt-ci-release`
- [x] 1.2 Add `sbt-projectmatrix` and `kyo-compat-plugin` (pin `kyo-compat` 1.0.0-RC6); declare the `CeLib` local axis and the `PekkoLib` external axis resolving `kyo-compat-future`
- [x] 1.3 Vendor `typesafe4s-compat-ce` with a README recording its provenance, and bind the `ce` axis to it with `bindLocally`
- [x] 1.4 Wire `compatConformance()` against the vendored binding and confirm it passes
- [x] 1.5 Create the `typesafe4s-client` matrix over Kyo/ZIO/CE/Ox/Pekko plus the unpublished `future` anchor row; pin each row's backend libraries explicitly
- [x] 1.6 Verify the matrix produces the intended artifact names and that the `future` row is excluded from publication

## 1b. Deterministic time (prerequisite for specs 6, 7 and 9)

- [x] 1b.1 Add a `Clock` seam to the shared runtime so timing never reads the carrier's clock directly
- [x] 1b.2 Implement a VirtualTime `ManualClock` once against the carrier, in the shared client's test sources
- [x] 1b.3 Make `integration-tests` depend on the client's test sources so the shared suite can use it
- [x] 1b.4 Write `DeterministicClockSuite` and run it on every published row (9 tests × 5 rows, green)
- [x] 1b.5 Record the Ox `unsafeRun`/ExecutionContext divergence in the capability profile
- [x] 1b.6 Close the gap in `openspec/capability-profile.md` and re-run spec-lint (now 8 PASS, 0 FAIL)

## 2. Core: JSON, entries, and state

- [x] 2.1 Implement the JSON AST with field-order-preserving objects
- [x] 2.2 Implement the parser and printer; confirm core's POM declares no runtime dependencies
- [x] 2.3 Add a print/parse round-trip property test including field order
- [x] 2.4 Implement `Entry` as text, object, array, or null, with smart constructors
- [x] 2.5 Implement the `StateEncoder` typeclass with core given instances for `String`, `Json`, `Map`, `Seq`, `Option`, and tuples

## 3. Core: the question and answer model

- [x] 3.1 Define the sealed `Question[A]` GADT and the `AnswerOf` match type
- [x] 3.2 Define `NoulAnswer` (no `confidence` member), `ChoiceAnswer[O]`, and `ScoreAnswer[L]`
- [x] 3.3 Implement Choice construction from a string-literal union, and `Choice.derived` via `Mirror.SumOf` returning ADT-valued answers
- [x] 3.4 Implement Score construction with compile-time level-count validation (2–10)
- [x] 3.5 Implement compile-time rejection of Choice sets over 255 options and of empty question sets
- [x] 3.6 Add compile-negative tests asserting each rejection fires with its intended message
- [x] 3.7 Decide and document the `Choice.derived` label policy (verbatim by default, with an explicit `Naming` given) — resolves design open question 3

## 4. Core: request and response codecs

- [x] 4.1 Implement request encoding for `state`, `model`, and `questions`, with each question's discriminator and criteria shape
- [x] 4.2 Implement strict response decoding dispatching on the answer `type` discriminator
- [x] 4.3 Make unknown response fields ignorable and absent usage fields decode as absent
- [x] 4.4 Report schema mismatches on a 2xx body as a response-validation error carrying a dotted field path
- [x] 4.5 Add recorded wire fixtures from the documented request and response examples, covering all three primitives

## 5. Core: errors and retry data

- [x] 5.1 Define the sealed `TypesafeException` hierarchy including a distinct `Overloaded` case for 529
- [x] 5.2 Implement status-to-error mapping, with the retry-after delay on rate-limit failures
- [x] 5.3 Surface the request id on API failures and on successful results, as absent when the header is missing
- [x] 5.4 Define `RetryPolicy` as data with the Python SDK's defaults (2 retries, 0.5s→5s, 0.25 jitter, `{408, 429, 500–599}`, 30s budget)
- [x] 5.5 Implement the token estimator and document it as conservative and approximate

## 6. Client: transport

- [x] 6.1 Define neutral `HttpRequest`/`HttpResponse` in core and the `Transport` SPI over `CIO` in the client
- [x] 6.2 Implement the JDK `HttpClient` transport
- [x] 6.3 Bracket the completion stage and cancel it on release so interruption aborts the exchange
- [x] 6.4 Apply the configured timeout per attempt
- [x] 6.5 Add a stub transport for network-free tests
- [x] 6.6 Add a per-backend interruption test proving the exchange is cancelled; document `future`/Pekko as no-ops

## 7. Client: configuration and retry loop

- [x] 7.1 Implement `TypesafeConfig` with argument → environment → default resolution for all four documented variables
- [x] 7.2 Implement `ApiKey` as opaque with a redacting `toString`; assert redaction in config rendering, debug logging, and error messages
- [x] 7.3 Fail client construction locally when no API key resolves, naming the variable to set
- [x] 7.4 Implement the retry loop over `CIO.sleep` / `CIO.nowMonotonic` / `CIO.recover`, with jitter and the total budget
- [x] 7.5 Honour `Retry-After` and `retry-after-ms`, with a switch to disable that
- [x] 7.6 Never start a delay that would reach the budget; raise the last attempt's error
- [x] 7.7 Support a per-call policy override
- [x] 7.8 Add property tests for the retry invariants: never exceed the budget, never retry a non-retryable status

## 8. Client: the call surfaces

- [x] 8.1 Implement the dynamic surface over `Map[String, Question[?]]` returning an `AnswerMap` with `Either`-returning typed lookups
- [x] 8.2 Implement the typed named-tuple surface on top of the dynamic decoder, deriving wire keys with `constValueTuple`
- [x] 8.3 Implement the models listing call without local model-name validation
- [x] 8.4 Implement scoped and manual client construction with deterministic resource release
- [x] 8.5 Add tests proving the typed surface produces identical wire bytes to the equivalent dynamic call

## 9. Client: batching

- [x] 9.1 Implement budget-aware splitting of a question set into requests that each fit, with every question in exactly one request
- [x] 9.2 Fail locally when a single question plus the state exceeds the budget, naming that question
- [x] 9.3 Issue batches with a configurable bounded concurrency and emit results as each resolves
- [x] 9.4 Propagate a batch failure through the stream after retries are exhausted

## 10. Backend facades

- [x] 10.1 Implement `LoweredClient[F]` deriving every operation from `lower` and `lift`
- [x] 10.2 Implement the ZIO facade with a typed error channel via `refineToOrDie`
- [x] 10.3 Implement the Cats Effect, Ox, Kyo, and Pekko facades
- [x] 10.4 Expose batching as `ZStream`, `fs2.Stream`, Ox `Flow`, and Pekko `Source` respectively
- [x] 10.5 Add a compile-time API parity check in the style of Sage's `ApiSurfaceCheck` and confirm it fails when an operation is omitted from one facade
- [x] 10.6 Confirm no backend type appears in shared sources and no carrier type appears in any public signature

## 11. JSON adapter modules — spec: `specs/json-adapters/spec.md` (spec 9/10)

- [ ] 11.1 Implement the circe adapter
- [ ] 11.2 Implement the zio-json adapter
- [ ] 11.3 Implement the jsoniter-scala and upickle adapters — or record the decision to defer them (design open question 2)
- [ ] 11.4 Verify each adapter pulls in only its own JSON library

## 12. Testing, examples, and release — spec: `specs/release-readiness/spec.md` (spec 10/10; 12.1 predates it under the parity machinery)

- [x] 12.1 Write the integration suite once against `CIO` and recompile and run it for every backend row
- [x] 12.2 Add live tests gated on `TYPESAFE_API_KEY` and skipped by default
- [x] 12.3 Confirm against a live key: the 429 retry-after header names, 529 semantics, and the `GET /v1/models` body shape; correct the codecs and specs if they differ — **executed 2026-09-19**: models shape + estimator margin both diverged and were corrected (see state.md for the record); no 429/529 was emitted during the run, so those two facts are recorded as *not observed* rather than confirmed
- [x] 12.4 Write runnable examples per backend, mirroring the documented quickstart and a confidence-gated routing example
- [x] 12.5 Write the README, including the groupId note and the disambiguation from Typesafe Inc. (now Lightbend)
- [x] 12.6 Configure CI to build, test, and lint every row; publish snapshots via `sbt-ci-release`
- [x] 12.7 Decide the 0.1 backend scope (design open question 1) and record it in the README — all five rows ship at 0.1 (approved at the spec-10 gate)
