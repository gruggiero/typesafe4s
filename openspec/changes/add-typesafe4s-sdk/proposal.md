## Why

There is no Scala SDK for the [TypeSafe](https://docs.typesafe.ai) System One API. Today a Scala
team must hand-roll HTTP calls, hand-write JSON for every question, and index answers by string key
with no type safety — losing exactly the property the API is built to provide.

Two things make this worth doing properly rather than as a thin wrapper:

1. **The API is unusually well suited to Scala 3's type system.** Questions have closed option sets
   and fixed answer shapes. A `Question[A]` GADT plus named tuples can make the answer set's field
   names, arity, and per-field types follow from the question set — and a `Mirror`-derived `Choice`
   can return a *domain ADT* instead of a string, which neither the Python nor the TypeScript SDK
   can express.

2. **Picking an effect system would draw a line through the users.** This SDK is designed to sit in
   hot paths — guardrails on every message, re-ranking every retrieval, routing every request. A ZIO
   service forced to `ZIO.fromFuture` on every call pays boxing and loses interruption on each one.
   [Sage](https://github.com/ghostdogpr/sage) solved this with the `kyo-compat` carrier type, and
   the same approach applies here.

## What Changes

- Add `typesafe4s-core`: a zero-dependency, sans-IO module with a JSON AST, the `Entry`/`State`
  model, the `Question[A]` GADT and answer types, request/response codecs, the sealed
  `TypesafeException` hierarchy, `RetryPolicy` as data, and neutral `HttpRequest`/`HttpResponse`.
- Add `typesafe4s-client`: the runtime written **once** against `kyo-compat`'s `CIO`, cross-published
  by `sbt-projectmatrix` into `typesafe4s-client-{zio,ce,ox,kyo,pekko}` with a thin facade each.
  The `future` row is a compile-only anchor and is never published.
- Add `typesafe4s-compat-ce`: a vendored `kyo.compat` Cats Effect binding, required because Kyo
  removed `kyo-compat-ce` upstream at `1.0.0-RC6`, validated by the plugin's conformance suite.
- Add a typed call surface over named tuples and a dynamic `Map`-based surface, both decoding
  through one decoder.
- Enforce the API's static constraints (Score levels 2–10, Choice options ≤ 255, non-empty question
  sets) as **compile errors**.
- Add a public `Transport` SPI with a `java.net.http.HttpClient` default that propagates cancellation
  by cancelling the underlying `CompletableFuture`.
- Port the Python SDK's `RetryPolicy` semantics exactly, including the total per-call budget.
- Add optional `StateEncoder` adapters for circe, zio-json, jsoniter-scala, and upickle so no JSON
  library is imposed.
- Add a token-budget-aware batching helper exposed as each backend's native stream type.
- Add one integration suite written against `CIO` and recompiled per backend row, plus runnable
  examples per backend.

## Capabilities

### New Capabilities
- `question-model`: the typed question/answer model — the `Question[A]` GADT, named-tuple and dynamic
  call surfaces, `Mirror`-derived choices, and compile-time enforcement of the API's static limits.
- `wire-codec`: the zero-dependency JSON AST, `Entry`/`StateEncoder`, and strict request encoding and
  response decoding against the documented wire format.
- `effect-portability`: one shared runtime over `CIO`, cross-published per backend with two-function
  facades, typed errors on ZIO, and enforced API parity across rows.
- `http-transport`: the neutral `Transport` SPI and the JDK default, including cancellation
  propagation and per-attempt timeouts.
- `retry-policy`: retry classification, exponential backoff with jitter, `Retry-After` handling, and
  the total per-call budget.
- `client-configuration`: config resolution from arguments, environment, and defaults, with secret
  redaction.
- `error-model`: the sealed exception hierarchy, HTTP status mapping, and request-id propagation.
- `question-batching`: splitting oversized question sets across requests within the shared token
  budget and emitting results as a native stream.
- `json-adapters`: one adapter module per documented JSON library (circe, zio-json, jsoniter-scala,
  upickle) converting each library's JSON type into `Entry` — faithful for carryable shapes, a
  dotted-path refusal otherwise — with per-adapter dependency isolation.
- `release-readiness`: the credential-gated live suite and wire-fact reconciliation (task 12.3),
  per-backend examples, README, and the CI matrix with snapshot publishing.

### Modified Capabilities
<!-- None: this is the first change in a new project. -->

## Impact

- **New repository content.** No existing code, so no breaking changes and no migration.
- **Published artifacts:** `typesafe4s-core`, `typesafe4s-client-{zio,ce,ox,kyo,pekko}`, and four
  optional JSON adapters. GroupId must not be `com.typesafe` (Lightbend's); use `io.github.<owner>`.
- **Dependencies:** `kyo-compat` `1.0.0-RC6` (pre-1.0, pinned), `sbt-projectmatrix`,
  `kyo-compat-plugin`. Core itself has zero runtime dependencies.
- **Language floor:** Scala 3.9.0. Named tuples require 3.7+, so Scala 3.3 LTS is out of scope for
  the typed surface.
- **Runtime floor:** JDK 21.
- **Unverified upstream details:** wire specifics taken from prose rather than an OpenAPI document —
  `429` header names, `529` semantics, and the `GET /v1/models` body shape must be confirmed against
  a live API key before 1.0.
