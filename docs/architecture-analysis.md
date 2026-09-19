# typesafe4s — Architecture Analysis

A Scala 3 SDK for the [TypeSafe](https://docs.typesafe.ai) System One API (model family `jev`),
built with the effect-portability approach that [Sage](https://github.com/ghostdogpr/sage) uses to
answer [The Scala Library Author's Dilemma](https://blog.pierre-ricadat.com/the-scala-library-authors-dilemma/).

**Status:** design document. Nothing is implemented yet. Every Scala snippet marked *verified*
was compiled and run against Scala 3.9.0 while writing this (see [Appendix A](#appendix-a--verification-log)).

---

## 1. What we are wrapping

TypeSafe is not a text-generation API. `jev` is a *System One* model: you send **state** plus a map of
**typed questions**, and you get back a map of **typed answers with calibrated probabilities**. There is
no token stream, no tool-calling loop, no parsing of prose.

### 1.1 The entire HTTP surface

| | |
|---|---|
| Base URL | `https://api.typesafe.ai` (env `TYPESAFE_BASE_URL`) |
| Evaluate | `POST /v1/systemone` |
| List models | `GET /v1/models` |
| Auth | `Authorization: Bearer <API_KEY>` (env `TYPESAFE_API_KEY`) |
| Default model | `jev-latest` (env `TYPESAFE_DEFAULT_MODEL`) |
| Streaming | none |
| Batch endpoint | none — batching is "many questions in one request" |

Request body:

```json
{
  "state": "…text, object, or array…",
  "model": "jev-latest",
  "questions": {
    "urgency": { "type": "noul", "instructions": "Does this message express urgency?" }
  }
}
```

Response body:

```json
{
  "model": "jev-latest",
  "answers": { "urgency": { "type": "noul", "noul": 0.999 } },
  "usage": { "input_tokens": 312, "output_tokens": 48 }
}
```

### 1.2 The three primitives

| Type | Request `criteria` | Answer fields | Constraints |
|---|---|---|---|
| **Noul** | optional `{true, false}` descriptions | `noul: Double` in `[0,1]` | *no* `confidence` field — the probability **is** the certainty |
| **Choice** | `Map[optionKey, description-or-null]` | `choice`, `probabilities`, `confidence` | ≤ 255 options |
| **Score** | ordered `Array[levelDescription]` | `score`, `legend`, `probabilities`, `confidence` | 2–10 levels; `score` is the probability-weighted mean, so it can be fractional |

`instructions`, every Choice description, every Score level, and Noul's `true`/`false` all accept the
same shape — the TS SDK calls it `EntryType`: **string, object, array, or null**.

Questions in one request are evaluated **independently and in parallel**; one answer never becomes
context for another. A single request shares a budget of roughly **32,000 tokens** across state and
questions combined.

### 1.3 Errors and retries (from the Python SDK, which is the richest reference)

Hierarchy: `TypeSafeError` → `TypeSafeAPIError` (400/401/403/404/422/429/5xx, plus
`APIResponseValidationError` for a 2xx whose body does not fit the schema) and
`TypeSafeAPIConnectionError` → `TypeSafeAPITimeoutError`. `RateLimitError` carries `retry_after_ms`.
The HTTP reference additionally names **529 Overloaded**, which the Python hierarchy folds into 5xx.

`RetryPolicy` defaults: `max_retries=2`, `backoff_initial=0.5s` doubling to `backoff_max=5.0s`,
`backoff_jitter=0.25` (fraction subtracted), retry on `{408, 429, 500–599}`, honour `Retry-After` and
`retry-after-ms`, and a **total budget of 30s per SDK call** that stops before a delay that would exceed it.

---

## 2. The dilemma, and why it applies here

Pierre Ricadat's framing: the moment a Scala library picks ZIO *or* Cats Effect *or* Future *or*
direct-style, it draws a line through its own users. The three classic escapes all cost something —
one canonical effect (foreign-runtime tax), a neutral imperative core (N adapters to maintain), or
`MonadError[F]`-style typeclasses (too weak once you need real concurrency).

Sage's answer is **kyo-compat**: a carrier type `CIO[A]` that is an `opaque type` resolving to a
*different concrete effect per compiled artifact*, with every operation an `inline def` so the
compiled output contains native primitives and zero wrapper objects.

```scala
// kyo-compat-zio 1.0.0-RC6, verbatim
opaque type CIO[+A] = ZIO[Any, Throwable, A]
inline def lift[A](inline z: ZIO[Any, Throwable, A]): CIO[A] = z
inline def value[A](inline a: A): CIO[A] = lift(ZIO.succeed(a))
```

One source tree is compiled N times by `sbt-projectmatrix`, producing `sage-client-zio`,
`sage-client-ce`, `sage-client-ox`, … from identical sources. Each backend then adds a thin facade
supplying two natural transformations.

### 2.1 Does an HTTP SDK need this much machinery?

Honest answer: **less than Redis does, but still enough to matter.** Sage needs pipelining,
pub/sub, reconnection, cluster topology — genuinely hard concurrency. typesafe4s is
request/response with no streaming. A skeptic could say "just use `Future` and let people wrap it."

Three reasons the carrier approach still wins here:

1. **The tax is paid at every call site, not once.** This SDK is meant to sit *inside* hot paths —
   guardrails on every LLM message, re-ranking every retrieval, routing every ticket. A ZIO service
   that must `ZIO.fromFuture` on every classification pays boxing, an extra shift, and loses
   interruption.
2. **Cancellation is the hard part and it is not uniform.** A `Future` cannot be cancelled. If
   typesafe4s returned `Future`, a ZIO or CE caller could never abort an in-flight request, and the
   retry loop would keep burning its budget after the caller walked away. Getting this right once
   against `CIO` and lowering it is exactly the "one implementation of every hard thing" argument.
3. **The retry loop is real concurrency.** Timed backoff, a wall-clock budget, jitter, and
   `Retry-After` all need a clock, sleep, and cancellation-aware bracketing — the things
   `MonadError` does not give you.

The genuine cost, stated plainly: **~150–250 lines of facade per backend**, a build that is harder to
read than a normal one, and a hard dependency on `kyo-compat`'s release cadence.

### 2.2 The dilemma has a second head here: JSON

This is the main way typesafe4s differs from Sage. Sage's core is zero-dependency because RESP3 is a
byte protocol. Ours is JSON — and picking circe would tax zio-json users exactly the way picking
Cats Effect would tax ZIO users. **The same answer applies at the data layer**: a small neutral core,
plus thin optional adapters.

---

## 3. Module architecture

```
typesafe4s-core           zero-dependency, sans-IO. JSON AST + parser/printer, Entry,
                          Question/Answer ADTs, request encoder, response decoder,
                          TypesafeException hierarchy, RetryPolicy (data only),
                          HttpRequest/HttpResponse (data only).

typesafe4s-compat-ce      vendored kyo.compat Cats Effect binding (see §3.1).

typesafe4s-client         the runtime, written ONCE against CIO, cross-published per backend.
  shared/                   Transport SPI, JdkHttpTransport, retry loop, redaction,
                            TypesafeConfig, Client[F], LoweredClient[F], Batching.
  zio/ ce/ ox/ kyo/ pekko/  per-backend facades (type alias + constructors + streaming).
  future/                   compile-only anchor row, never published.

typesafe4s-circe          optional: StateEncoder from circe Encoder / Json.
typesafe4s-zio-json       optional: StateEncoder from zio-json JsonEncoder.
typesafe4s-jsoniter       optional: StateEncoder from jsoniter-scala codecs.
typesafe4s-upickle        optional: StateEncoder from upickle Writer.

integration-tests/        one suite against CIO, recompiled per backend.
examples/                 runnable per backend.
```

Published artifacts: `typesafe4s-core`, `typesafe4s-client-{zio,ce,ox,kyo,pekko}`, and the four
JSON adapters. The `future` row compiles but is never published — it is the plugin's implicit anchor.

### 3.1 A non-obvious build constraint

Kyo **removed** `kyo-compat-ce` upstream as of `1.0.0-RC6`. Sage works around this by vendoring the
binding as a local module and binding the `ce` axis to it:

```scala
val CeLib = CompatBackendAxis.local("ce", "Ce", "-ce", Set("jvm"))

lazy val client = (projectMatrix in file("typesafe4s-client"))
  .compatLibrary(KyoLib, ZioLib, CeLib, OxLib, PekkoLib)(VirtualAxis.jvm)(Seq(scala3Version))
  .bindLocally(CeLib, compatCe)
```

`KyoLib`, `ZioLib`, `OxLib`, `FutureLib`, and `TwitterFutureLib` ship with the plugin; `CeLib` and
`PekkoLib` must be declared in `build.sbt`. Pekko is declared as an *external* axis that resolves
`kyo-compat-future`, because Pekko rides on `Future` + Pekko Streams:

```scala
val PekkoLib = CompatBackendAxis.external("pekko", "Pekko", "-pekko", Set("jvm"),
                                          "io.getkyo", "kyo-compat-future", kyoVersion)
```

Sage also runs the conformance suite bundled in `kyo-compat-plugin` against its vendored binding via
`.compatConformance()`, so an upstream contract change surfaces as a test failure rather than a
downstream compile error. typesafe4s should copy this verbatim.

---

## 4. The typed question/answer model

This is where a Scala 3 SDK can beat both reference SDKs, so it deserves the most care.

### 4.1 What the TypeScript SDK does

```ts
type ResultFor<T> =
  T extends NoulQuestion       ? NoulResponse       :
  T extends ScoreQuestion<infer S>  ? ScoreResponse<S>  :
  T extends ChoiceQuestion<infer E> ? ChoiceResponse<E> : never;
```

Question types carry their option keys as type parameters, and a mapped type turns the questions
object into a matching answers object. Python does none of this — it returns
`response.nouls[...]` / `.choices[...]` / `.scores[...]` dictionaries and you index by string.

### 4.2 The Scala 3 equivalent — a GADT plus named tuples

Questions are indexed by the answer they produce:

```scala
sealed trait Question[A]
final case class Noul(instructions: Entry, criteria: Option[NoulCriteria] = None)
  extends Question[NoulAnswer]
final case class Choice[O](instructions: Entry, options: ListMap[String, Entry], decode: String => Option[O])
  extends Question[ChoiceAnswer[O]]
final case class Score[L <: Int](instructions: Entry, levels: Vector[Entry])
  extends Question[ScoreAnswer[L]]

type AnswerOf[Q] = Q match
  case Question[a] => a
```

The question set is a **named tuple**, and the answer set is that tuple mapped through `AnswerOf`,
so field names, arity, and per-field answer types are all preserved:

```scala
inline def systemOne[N <: Tuple, V <: Tuple](state: State)(
  questions: NamedTuple[N, V]
): F[NamedTuple[N, Tuple.Map[V, AnswerOf]]]
```

The wire keys come from the field names at compile time via `constValueTuple[N]` — the caller never
writes a string key twice.

**Verified.** This compiles and runs on Scala 3.9.0:

```scala
val a = client.systemOne(ticket)((
  refundRequested = Noul("Does the customer request a refund?"),
  department      = Choice.of["billing" | "technical"]("Which team?"),
  frustration     = Score[3]("How frustrated?")("Calm", "Concerned", "Angry")
))

val n: Double                  = a.refundRequested.noul
val c: "billing" | "technical" = a.department.choice
val s: Double                  = a.frustration.score

c match                       // exhaustive — the compiler knows the options
  case "billing"   => billingQueue
  case "technical" => engineeringQueue
```

Compare the Python equivalent: `response.answers["refund_requested"].noul`, where a typo in the key
is a `KeyError` at runtime and `.noul` on a Choice answer is an `AttributeError`.

### 4.3 Beyond TypeScript: Choice derived from an ADT

Because the option keys are just labels, a Scala 3 `enum` can supply them via `Mirror`, and the
answer can then carry **the ADT value itself** rather than a string:

```scala
enum Department:
  case Billing, Technical, Sales

val q = Choice.derived[Department]("Which team should handle this ticket?")
// q.options == List("Billing", "Technical", "Sales")

answer.choice match            // a Department, not a String
  case Department.Billing   => …
  case Department.Technical => …
  case Department.Sales     => …
```

**Verified** — `Mirror.SumOf` + `constValueTuple[MirroredElemLabels]` + `summonInline[ValueOf[h]]`
recovers each singleton case, and decoding is total. Descriptions attach through a companion or an
explicit map; a case with no description serialises as `null`, which the API allows.

### 4.4 API constraints become compile errors

The documented limits are static, so they should never reach the network:

```scala
inline def apply[L <: Int](instructions: Entry)(inline levels: String*): Score[L] =
  inline if constValue[L] < 2  then error("a Score needs at least 2 levels")
  inline if constValue[L] > 10 then error("a Score accepts at most 10 levels")
  …
```

**Verified** — `Score2[1]("How frustrated?")("Calm")` fails to compile with
`a Score needs at least 2 levels`. The same technique rejects a Choice with more than 255 options
and an empty question set.

### 4.5 The dynamic surface is not optional

Several documented use cases build question sets at runtime — re-ranking 30 candidates per query,
scoring 218 document lines, beam search over a deep taxonomy. A named tuple cannot express those,
so there must be a second, equally supported surface:

```scala
def systemOneDynamic(state: State, questions: Map[String, Question[?]]): F[AnswerMap]

final class AnswerMap:
  def noul(key: String): Either[TypesafeException.MissingAnswer, NoulAnswer]
  def choice(key: String): Either[TypesafeException.MissingAnswer, ChoiceAnswer[String]]
  def score(key: String): Either[TypesafeException.MissingAnswer, ScoreAnswer[Int]]
  def usage: Usage
```

Typed lookups return `Either` rather than throwing. The typed surface is implemented *on top of*
this one, so there is a single decoder.

---

## 5. Transport

### 5.1 Default: the JDK HTTP client

`java.net.http.HttpClient` is in the JDK, is asynchronous, and is effect-neutral — the exact
analogue of Sage writing its own socket layer. `sendAsync` returns a `CompletableFuture`, and
`kyo-compat` ships a JVM-only lift for exactly that:

```scala
// kyo-compat, verbatim
extension (inline c: CIO.type)
  inline def fromCompletionStage[A](inline cs: CompletionStage[A]): CIO[A]
```

**One sharp edge, called out in the compat source itself:** *"cancellation does NOT propagate back"*.
Lifting naively means a cancelled ZIO fiber or CE `IO` leaves the HTTP exchange running. The
transport must therefore bracket the future and cancel it on release:

```scala
def send(req: HttpRequest): CIO[HttpResponse] =
  CIO.acquireReleaseWith(CIO.defer(underlying.sendAsync(toJdk(req), ofByteArray)))(
    cf => CIO.defer(cf.cancel(true)).unit
  )(cf => CIO.fromCompletionStage(cf).map(fromJdk))
```

`HttpClient` honours `cancel(true)` by aborting the exchange, so interruption becomes real on every
backend except `Future`/Pekko, where it is a documented no-op.

### 5.2 Transport is a public SPI

Unlike a Redis socket, HTTP is something most production users already have opinions about —
proxies, mTLS, service meshes, tracing, connection pools. So `Transport` is public:

```scala
trait Transport:
  def send(request: HttpRequest): CIO[HttpResponse]
```

with `HttpRequest`/`HttpResponse` as plain data in core. A user on http4s, sttp, or zio-http
implements one method. This is a deliberate, small extension point — not a second abstraction layer.

---

## 6. Wire codec

Core ships a minimal JSON AST plus a hand-written parser and printer, zero dependencies:

```scala
enum Json:
  case Null
  case Bool(value: Boolean)
  case Num(value: Double)
  case Str(value: String)
  case Arr(values: Vector[Json])
  case Obj(fields: ListMap[String, Json])
```

`Entry` (the API's `EntryType`) is an opaque subset with smart constructors, and user types reach it
through a typeclass:

```scala
opaque type Entry = Json
trait StateEncoder[A]:
  def encode(value: A): Entry
```

Given instances in core: `String`, `Json`, `Map[String, A]`, `Seq[A]`, tuples, `Option`. The four
adapter modules each add one bridging given (e.g. `circe.Encoder[A] ⇒ StateEncoder[A]`), which is
about ten lines apiece. No user is forced into a JSON library they do not already have.

Decoding is strict and dispatches on the `type` discriminator, and a 2xx body that does not fit the
schema becomes `ResponseValidation(fieldPath)` with a dotted path — matching the Python SDK.

**Deliberate deviation worth flagging:** the HTTP reference names **529 Overloaded** but the Python
hierarchy has no dedicated class for it. typesafe4s adds `TypesafeException.Overloaded` as a distinct
case (still a retryable 5xx) so callers can match it. This is a superset, not a conflict.

---

## 7. Config, retries, errors

```scala
final case class TypesafeConfig(
  apiKey:  ApiKey,                                  // redacted toString
  baseUrl: String            = "https://api.typesafe.ai",
  model:   String            = "jev-latest",
  timeout: FiniteDuration    = 10.seconds,          // per attempt
  retry:   RetryPolicy       = RetryPolicy.default,
  headers: Map[String, String] = Map.empty,
  logLevel: LogLevel         = LogLevel.Warn
)
```

Resolution order per field: explicit argument → environment variable → default, matching both
reference SDKs. `ApiKey` is opaque with a redacting `toString`, following Sage's treatment of
`AuthConfig` and `TrustStore` — a config object must never leak a key into a log line or a stack trace.

`RetryPolicy` ports the Python defaults exactly (§1.3). The loop lives once in shared code over
`CIO.sleep` / `CIO.nowMonotonic` / `CIO.recover`, and honours the total budget by **not starting** a
delay that would reach it.

The error ADT is sealed, so callers can match exhaustively — the shape Sage uses for `SageException`:

```scala
sealed abstract class TypesafeException(message: String) extends Exception(message)
object TypesafeException:
  final case class BadRequest(status: Int, body: String, requestId: Option[String])       extends …
  final case class Authentication(…) ; PermissionDenied ; NotFound ; UnprocessableEntity
  final case class RateLimit(retryAfter: Option[FiniteDuration], …)                        extends …
  final case class Overloaded(…) ; InternalServer(…)
  final case class ResponseValidation(fieldPath: String, …)                                extends …
  final case class ConnectionFailed(message: String)                                       extends …
  final case class Timeout(after: FiniteDuration)                                          extends …
  final case class MissingAnswer(key: String)                                              extends …
```

The `x-typesafe-request-id` response header is surfaced on every answer set and every API error —
it is the only handle support has.

---

## 8. Per-backend facades

Each backend publishes a type alias and constructors so users only ever see native types:

```scala
// typesafe4s-client-zio
type TypesafeClient = Client[IO[TypesafeException, *]]

// typesafe4s-client-ce
type TypesafeClient = Client[cats.effect.IO]

// typesafe4s-client-ox  (direct style)
type TypesafeClient = Client[[A] =>> Ox ?=> A]
```

Note the ZIO row uses a **typed error channel**. The blog lists "typed errors lost" as a trade-off,
but Sage's shipped ZIO facade actually recovers them with `refineToOrDie[SageException]`, and
typesafe4s should do the same — ZIO users get `IO[TypesafeException, A]` and every other backend
gets `Throwable`.

The shared `LoweredClient[F]` needs only two transformations, and everything else is derived:

```scala
abstract class LoweredClient[F[_]](underlying: Client[CIO]) extends Client[F]:
  protected def lower[A](c: CIO[A]): F[A]
  protected def lift[A](fa: F[A]): CIO[A]
  final def systemOne(...): F[...] = lower(underlying.systemOne(...))
```

Streaming helpers cannot be shared, because the return type differs per ecosystem
(`ZStream` / `fs2.Stream` / Ox `Flow` / Pekko `Source`). That is the one place facades carry real code —
and it is exactly where §9 lands.

---

## 9. Batching: the one genuinely streaming feature

There is no streaming endpoint, but there is a hard **~32,000-token budget shared across state and
questions**, while the documented use cases deliberately ask hundreds of questions at once. So the
SDK should own the split:

```scala
def systemOneBatched(
  state: State,
  questions: Map[String, Question[?]],
  budget: TokenBudget = TokenBudget.default
): Stream[F, AnswerMap]    // ZStream / fs2.Stream / Flow / Source per backend
```

It chunks a large question map into requests that fit the budget alongside the state, issues them
with bounded concurrency, and emits answer maps as they resolve. Core provides a conservative
token estimator; exceeding the budget on a *single* question is a client-side error raised before
any network call, since the server would reject it anyway.

This mirrors Sage's `scanAll` / `xTail`: shared paging logic in core, thin per-backend stream adapters.

---

## 10. Testing strategy

Copied from Sage, because it is the part that makes N artifacts affordable:

1. **One suite, N compilations.** Integration tests are written once against `CIO` and recompiled per
   backend cell, so all five backends are proven to behave identically rather than assumed to.
2. **A stub `Transport`** makes almost everything testable with no network: retry/backoff timing,
   error mapping, redaction, budget splitting.
3. **Recorded fixtures** for wire encode/decode, taken from the documented request/response examples.
4. **Property tests** for JSON round-tripping and for retry invariants (never exceed the budget;
   never retry a non-retryable status).
5. **`compatConformance()`** against the vendored CE binding.
6. **A compile-time API parity check** in the style of Sage's `ApiSurfaceCheck`, so a helper added to
   the ZIO facade and forgotten on Ox fails the build.
7. **Compile-negative tests** asserting that a 1-level Score and a 256-option Choice are rejected.
8. **Live tests** gated on `TYPESAFE_API_KEY`, skipped by default.

---

## 11. Risks, trade-offs, and open questions

| Risk | Assessment |
|---|---|
| `kyo-compat` is pre-1.0 (`1.0.0-RC6`) and already removed the CE binding | Real. Mitigated by vendoring CE and running the bundled conformance suite. Pin the version; treat upgrades as deliberate changes. |
| Named tuples need Scala **3.7+**, so no 3.3 LTS | Accepted. Sage is on 3.9.0. The `Map`-based dynamic surface has no such requirement and could be published for LTS later if demand appears. |
| N artifacts multiply CI time and release surface | Real, and the price of the approach. Sage demonstrates it is manageable. |
| `CompletionStage` cancellation does not propagate by default | Handled explicitly in §5.1; must be covered by a test per backend. |
| Pekko/Future rows cannot cancel | Inherent. Document it rather than hide it, as Sage does. |
| Docs are the only source of truth; no OpenAPI spec was found | Wire details (429 header names, 529 semantics, `GET /v1/models` body shape) are taken from prose and should be confirmed against a live key before 1.0. |

### Naming / coordinates

**`com.typesafe` is unavailable** — it is Lightbend's long-standing groupId (`typesafe-config`, Akka,
Play), and publishing TypeSafe-AI artifacts there would be both impossible and actively confusing.
Recommend `io.github.<owner>` with artifact names `typesafe4s-*`, and a README line disambiguating
TypeSafe (the AI platform) from Typesafe Inc. (now Lightbend).

### Open questions for the maintainer

1. Ship all five backends at 0.1, or start with ZIO + CE and add Ox/Kyo/Pekko once the core settles?
   (Recommendation: **all five from day one** — the whole point is that the marginal cost is a facade,
   and retrofitting is harder than starting wide.)
2. Is `jsoniter`/`upickle` interop worth the modules at 0.1, or are circe + zio-json enough?
3. Should `Choice.derived` use enum case names verbatim, or lower-snake-case them to match the
   documented style (`billing`, `none_of_the_above`)? (Recommendation: verbatim by default, with an
   explicit `Naming` given for the transformation, so the wire contract is never implicit.)

---

## Appendix A — verification log

Compiled and run against **Scala 3.9.0** via scala-cli while writing this document:

| Claim | §  | Result |
|---|---|---|
| Named-tuple question set maps to a named-tuple answer set with per-field types | 4.2 | compiles and runs |
| `constValueTuple[N]` recovers wire keys from field names | 4.2 | prints `List(refundRequested, department, frustration)` |
| Choice answer refines to the option union; `match` is exhaustive | 4.2 | compiles and runs |
| `Choice.derived[E]` via `Mirror.SumOf` yields ADT-valued answers | 4.3 | `decode("Technical") == Some(Department.Technical)` |
| Score level count validated at compile time | 4.4 | `Score2[1](…)` fails with `a Score needs at least 2 levels` |

API facts were taken from `docs.typesafe.ai` (`/api`, `/primitives*`, `/sdk/python/**`,
`/sdk/javascript/**`, `/models`, `/confidence`). Build and compat facts were taken from the Sage
repository at `main` and from the published sources of `kyo-compat-zio` and `kyo-compat-plugin`
`1.0.0-RC6`.
