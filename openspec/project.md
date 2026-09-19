# Project Context

## Purpose
**typesafe4s** is a Scala 3 SDK for the [TypeSafe](https://docs.typesafe.ai) System One API — the
`jev` model family, which returns typed, calibrated answers (not text) to typed questions asked
against a state.

The project's defining constraint: it must **not force an effect system on its users**. It follows
the approach [Sage](https://github.com/ghostdogpr/sage) uses to answer
[The Scala Library Author's Dilemma](https://blog.pierre-ricadat.com/the-scala-library-authors-dilemma/) —
a single source tree written against the `kyo-compat` carrier type `CIO[A]`, cross-compiled by
`sbt-projectmatrix` into one published artifact per backend (ZIO, Cats Effect, Ox, Kyo, Pekko), each
with a thin facade so users only ever see native types.

The full design rationale lives in [`docs/architecture-analysis.md`](../docs/architecture-analysis.md).

## Module Dependency Graph

```
typesafe4s-client (matrix: zio | ce | ox | kyo | pekko | future-anchor)
  → typesafe4s-core
  → typesafe4s-compat-ce            (ce row only, bindLocally)

typesafe4s-circe / -zio-json / -jsoniter / -upickle
  → typesafe4s-core

integration-tests (matrix, same rows)  → typesafe4s-client
examples          (matrix, same rows)  → typesafe4s-client
```

### Modules

- **typesafe4s-core** — zero-dependency, sans-IO. JSON AST + parser/printer, `Entry`/`State`,
  `StateEncoder`, the `Question[A]` GADT and answer types, request encoder, response decoder,
  `TypesafeException` hierarchy, `RetryPolicy` (data only), `HttpRequest`/`HttpResponse` (data only),
  token estimator.
- **typesafe4s-compat-ce** — vendored `kyo.compat` Cats Effect binding. Required because Kyo removed
  `kyo-compat-ce` upstream at `1.0.0-RC6`. Validated by the plugin's bundled conformance suite.
- **typesafe4s-client** — the runtime, written once against `CIO`. `Transport` SPI,
  `JdkHttpTransport`, retry loop, `TypesafeConfig`, `Client[F]`, `LoweredClient[F]`, batching.
  Cross-published per backend; the `future` row is a compile-only anchor and is never published.
- **typesafe4s-{circe,zio-json,jsoniter,upickle}** — optional one-given adapters supplying
  `StateEncoder[A]` from that library's encoder. No JSON library is imposed on users.
- **integration-tests** — one suite written against `CIO`, recompiled per backend row.
- **examples** — runnable per backend.

## Published Artifacts

`typesafe4s-core`, `typesafe4s-client-{zio,ce,ox,kyo,pekko}`, `typesafe4s-{circe,zio-json,jsoniter,upickle}`.

GroupId note: **`com.typesafe` is not available** — it is Lightbend's long-standing groupId. Use
`io.github.<owner>`. The README must disambiguate TypeSafe (the AI platform) from Typesafe Inc.
(now Lightbend).

## Tech Stack

### Language & Build
- **Scala 3.8.4 + 3.9.0** — cross-compiled on both minors; artifacts publish from the 3.8.4 build
  (TASTy is forward-compatible, so it serves 3.9.x callers too). Named tuples (3.7+) are
  load-bearing for the typed question API, so 3.3 LTS is out of scope.
- **sbt** with `sbt-projectmatrix`, `kyo-compat-plugin`, `sbt-buildinfo`, `sbt-ci-release`,
  `sbt-scalafmt`.
- **JDK 21** target (`-release 21`).
- Compiler options: `-deprecation`, `-no-indent`, `-Wunused:imports,params,privates,implicits,explicits`,
  `-Wvalue-discard`, `-Werror`, `-Xkind-projector`.

### Core Libraries
- `kyo-compat` `1.0.0-RC6` — the `CIO`/`CStream` carrier. Pinned; upgrades are deliberate changes.
- `java.net.http.HttpClient` — the default transport. No HTTP dependency.
- Per-backend, pinned in the matrix row rather than inherited transitively: ZIO + zio-streams,
  cats-effect + fs2, ox, kyo, pekko-stream + pekko-actor-typed.

### Testing
- **MUnit** — the same framework Sage uses.
- Stub `Transport` for network-free tests; recorded wire fixtures; property tests for JSON
  round-tripping and retry invariants; compile-negative tests for the static constraints;
  `compatConformance()` for the vendored CE binding; live tests gated on `TYPESAFE_API_KEY`.

## Key Conventions

- **The shared runtime never names a backend.** Anything backend-specific lives in a facade.
- **Two transformations per facade** — `lower[A](CIO[A]): F[A]` and `lift[A](F[A]): CIO[A]`.
  Everything else is derived in `LoweredClient`.
- **ZIO gets typed errors** (`IO[TypesafeException, *]`); every other backend gets `Throwable`.
- **Static API constraints are compile errors**, not runtime failures: Score levels 2–10, Choice
  options ≤ 255, non-empty question sets.
- **Secrets never render.** `ApiKey` is opaque with a redacting `toString`; config and errors must
  not leak it.
- **Every API error carries `x-typesafe-request-id`** when the server sent one.
- Errors are a **sealed** hierarchy so callers can match exhaustively.
- Two equally supported call surfaces: the **typed** named-tuple one and the **dynamic**
  `Map[String, Question[?]]` one. The typed surface is built on the dynamic decoder — there is one
  decoder, not two.

## Upstream References

- API: https://docs.typesafe.ai/api · Primitives: https://docs.typesafe.ai/primitives
- Python SDK (richest reference for retries/errors): https://docs.typesafe.ai/sdk/python
- TypeScript SDK (reference for type-level inference): https://docs.typesafe.ai/sdk/javascript
- Sage: https://github.com/ghostdogpr/sage
