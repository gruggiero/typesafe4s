## Context

The TypeSafe System One API is a single `POST /v1/systemone` endpoint plus `GET /v1/models`. You send
a **state** and a map of **typed questions**; you get a map of **typed answers with calibrated
probabilities**. There is no streaming, no batch endpoint, and no tool-calling loop. Questions are
evaluated independently and in parallel, sharing a budget of roughly 32,000 tokens across state and
questions.

The three primitives:

| Type | `criteria` | Answer | Static limits |
|---|---|---|---|
| Noul | optional `{true, false}` | `noul: Double` in `[0,1]`, **no** `confidence` | — |
| Choice | `Map[key, description-or-null]` | `choice`, `probabilities`, `confidence` | ≤ 255 options |
| Score | ordered level descriptions | `score` (fractional), `legend`, `probabilities`, `confidence` | 2–10 levels |

Two facts shape the whole design. First, the answer shape is a total function of the question type —
which Scala 3's type system can express better than either reference SDK. Second, this SDK belongs in
hot paths, so the effect system it returns is not a cosmetic choice. The full rationale is in
[`docs/architecture-analysis.md`](../../../docs/architecture-analysis.md).

## Goals / Non-Goals

**Goals:**
- Native types for ZIO, Cats Effect, Ox, Kyo, and Pekko users, with no foreign-runtime tax and no
  lost interruption.
- One implementation of every hard thing — retries, cancellation, budget splitting, decoding — in
  shared code, not repeated per backend.
- Answer types inferred from question types, including the option set of a Choice.
- The API's static limits rejected at compile time.
- No JSON library imposed on users; core has zero runtime dependencies.
- Provable behavioural parity across backends, not assumed parity.

**Non-Goals:**
- Scala 3.3 LTS support for the typed surface (named tuples need 3.7+).
- Scala.js / Native in this change (the default transport is JVM-only; `KyoLib`/`ZioLib` would allow
  it later behind a different transport).
- Prompt building, agent loops, or retrieval — this is an SDK, not a framework.
- Wrapping a second HTTP library. `Transport` is an SPI so users can bring their own.

## Decisions

### 1. `kyo-compat`'s `CIO` as the carrier, one artifact per backend

Adopted from Sage. `CIO[A]` is an `opaque type` resolving to a different concrete effect per compiled
artifact, with every operation an `inline def`, so the output contains native primitives and zero
wrapper objects. `sbt-projectmatrix` compiles one source tree into `typesafe4s-client-zio`,
`-ce`, `-ox`, `-kyo`, `-pekko`.

*Alternatives rejected:* a single canonical effect (taxes everyone else at every call site);
`MonadError[F]` typeclasses (too weak for timed backoff, a wall-clock budget, and cancellation);
returning `Future` (cannot be cancelled, so a retry loop keeps burning budget after the caller is gone).

*Cost, stated plainly:* ~150–250 lines of facade per backend, a build that is harder to read than a
normal one, and a dependency on a pre-1.0 library's cadence.

### 2. Vendor the Cats Effect compat binding

Kyo removed `kyo-compat-ce` at `1.0.0-RC6`. `typesafe4s-compat-ce` vendors it, the `ce` axis is
declared with `CompatBackendAxis.local` and attached with `.bindLocally`, and the plugin's bundled
`compatConformance()` suite runs against it so an upstream contract change surfaces as a test failure
rather than a downstream compile error. Pekko is declared with `CompatBackendAxis.external` pointing
at `kyo-compat-future`, because Pekko rides on `Future` plus Pekko Streams.

### 3. `Question[A]` GADT + named tuples for the typed surface

```scala
sealed trait Question[A]
type AnswerOf[Q] = Q match { case Question[a] => a }

inline def systemOne[N <: Tuple, V <: Tuple](state: State)(
  questions: NamedTuple[N, V]
): F[NamedTuple[N, Tuple.Map[V, AnswerOf]]]
```

Wire keys come from the field names via `constValueTuple[N]`, so a key is never written twice. This
is the Scala equivalent of the TypeScript SDK's `ResultFor<T>` conditional type, and it was verified
to compile and run on Scala 3.9.0 — including a Choice answer refining to the option union so a
`match` on it is exhaustive.

*A `Mirror`-derived Choice goes further than either reference SDK:* `Choice.derived[Department]` takes
option keys from an `enum`'s case labels and returns `Department` values, not strings.

### 4. The dynamic surface is a first-class peer, and the only decoder

Documented use cases build question sets at runtime — re-ranking 30 candidates, scoring 218 document
lines, beam search over a taxonomy. Named tuples cannot express those. So `systemOneDynamic(state,
Map[String, Question[?]])` returns an `AnswerMap` whose typed lookups return `Either`, never throw.
**The typed surface is implemented on top of it**, so there is exactly one decoder to keep correct.

### 5. Static API limits become compile errors

`inline if constValue[L] < 2 then error("a Score needs at least 2 levels")` and the equivalents for
`> 10` levels, `> 255` Choice options, and an empty question set. Verified: `Score[1](…)` fails to
compile with that message. These are constants in the source, so they should never cost a round trip.

### 6. JDK `HttpClient` by default, `Transport` as a public SPI

`java.net.http.HttpClient` is in the JDK, asynchronous, and effect-neutral — the analogue of Sage
owning its socket layer. It keeps core dependency-free.

**Cancellation needs explicit work.** `kyo-compat`'s own source says of `fromCompletionStage`:
*"cancellation does NOT propagate back"*. A naive lift leaves the HTTP exchange running after the
caller is interrupted. The transport therefore brackets the future and calls `cf.cancel(true)` on
release, which `HttpClient` honours by aborting the exchange.

`Transport` is public because, unlike a Redis socket, HTTP is something production users already have
opinions about — proxies, mTLS, meshes, tracing. Implementing one method swaps in http4s, sttp, or
zio-http.

### 7. A neutral JSON core, with adapters

The effect dilemma has a JSON twin: choosing circe taxes zio-json users the way choosing Cats Effect
taxes ZIO users. So core ships a small JSON AST with a hand-written parser and printer and a
`StateEncoder[A]` typeclass; four optional modules each add one bridging given (~10 lines apiece).

### 8. ZIO gets typed errors

The dilemma article lists "typed errors lost" as a trade-off, but Sage's shipped ZIO facade recovers
them with `refineToOrDie`. typesafe4s does the same: ZIO users get `IO[TypesafeException, A]`, every
other backend gets `Throwable`. The error ADT is sealed so matching is exhaustive.

### 9. Batching owns the token budget

No streaming endpoint exists, but the ~32,000-token shared budget collides with use cases that ask
hundreds of questions. `systemOneBatched` splits a question map into budget-sized requests, issues
them with bounded concurrency, and emits `AnswerMap`s as each resolves — exposed as `ZStream`,
`fs2.Stream`, Ox `Flow`, or Pekko `Source`. A single question too large for the budget fails
client-side before any network call.

### 10. Parity is tested, not assumed

One integration suite written against `CIO` is recompiled and run per backend row, and a compile-time
API parity check in the style of Sage's `ApiSurfaceCheck` fails the build when a helper is added to
one facade and forgotten on another.

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| `kyo-compat` is pre-1.0 and already removed one binding | Vendor the CE binding, run the bundled conformance suite, pin the version, treat upgrades as deliberate changes. |
| Five artifacts multiply CI time and release surface | Accepted; it is the cost of the approach. Rows share one source tree, and Sage shows it is manageable. |
| Named tuples exclude Scala 3.3 LTS | Accepted. The dynamic `Map` surface has no such requirement and could ship for LTS later if demand appears. |
| `CompletionStage` cancellation does not propagate by default | Explicit bracket-and-cancel in the transport, with a per-backend interruption test. |
| `Future` and Pekko rows cannot cancel | Inherent to those ecosystems. Documented rather than hidden, as Sage does. |
| Wire details come from prose, not an OpenAPI document | `429` header names, `529` semantics, and the `GET /v1/models` body shape must be confirmed against a live key before 1.0; decoding is strict so mismatches surface as `ResponseValidation` with a field path rather than silent wrong values. |
| `com.typesafe` groupId is Lightbend's | Publish under `io.github.<owner>`; disambiguate in the README. |

## Open Questions

1. Ship all five backends at 0.1, or start with ZIO + CE? *Recommendation: all five* — the marginal
   cost is a facade, and retrofitting is harder than starting wide.
   **RESOLVED: all five** — implemented and parity-verified (specs 6–8); the decision is to be
   recorded in the README under `specs/release-readiness` (spec 10, task 12.7).
2. Are jsoniter and upickle adapters worth shipping at 0.1, or are circe and zio-json enough?
   **RESOLVED: all four ship** — `specs/json-adapters` (spec 9, added at change-level verification)
   specifies one adapter per documented library (task 11.3's deferral option not taken).
3. Should `Choice.derived` use enum case labels verbatim or lower-snake-case them to match the
   documented option style? *Recommendation: verbatim by default*, with an explicit `Naming` given
   for the transformation, so the wire contract is never implicit.
   **RESOLVED at spec 3** — verbatim by default with an explicit `Naming` given (task 3.7).
