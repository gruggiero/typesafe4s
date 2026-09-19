# typesafe4s

A Scala 3 SDK for the [TypeSafe](https://docs.typesafe.ai) System One API (`jev` model family):
send state and typed questions, get typed answers with calibrated probabilities.

> **Status: 0.1 development.** The full pipeline — error model, wire codec, question model, retry,
> transport, per-backend facades, batching and JSON adapters — is implemented and verified; the API
> may still shift before the first tagged release.

> **Not affiliated with Typesafe Inc. (now Lightbend).** "TypeSafe" here means the AI platform at
> [typesafe.ai](https://docs.typesafe.ai), not the company behind Akka, Play, or `typesafe-config`.

## Why it exists

The SDK works with **your** effect system — ZIO, Cats Effect, Ox, Kyo, or Pekko — with no
foreign-runtime tax. One shared runtime is written against the `kyo-compat` carrier type and
cross-published per backend, following the approach [Sage](https://github.com/ghostdogpr/sage) uses
to answer [The Scala Library Author's Dilemma](https://blog.pierre-ricadat.com/the-scala-library-authors-dilemma/).

## Installation

Artifacts are published under **`io.github.gruggiero`** — not `com.typesafe` (that is Lightbend's
groupId; this SDK is not affiliated with Typesafe Inc. — see the note above). Pick the artifact for
your backend:

```scala
// one of:
"io.github.gruggiero" %% "typesafe4s-client-zio"   % "0.1.0"
"io.github.gruggiero" %% "typesafe4s-client-ce"    % "0.1.0"
"io.github.gruggiero" %% "typesafe4s-client-ox"    % "0.1.0"
"io.github.gruggiero" %% "typesafe4s-client-kyo"   % "0.1.0"
"io.github.gruggiero" %% "typesafe4s-client-pekko" % "0.1.0"
```

Optional JSON adapters — each pulls in exactly its own library and nothing else:

```scala
"io.github.gruggiero" %% "typesafe4s-circe"    % "0.1.0"  // io.circe
"io.github.gruggiero" %% "typesafe4s-zio-json" % "0.1.0"  // zio-json
"io.github.gruggiero" %% "typesafe4s-jsoniter" % "0.1.0"  // jsoniter-scala
"io.github.gruggiero" %% "typesafe4s-upickle"  % "0.1.0"  // uPickle
```

## Supported backends

All five rows ship at 0.1 — **ZIO, Cats Effect, Ox, Kyo and Pekko**. A sixth `future` row exists as
the compile-only baseline the shared sources are checked against; it is never published.

## Quickstart

Set `TYPESAFE_API_KEY` and ask a question set — the same call expressed in your backend's own types
(ZIO shown; each row has the equivalent runnable example under `examples/`):

```scala
import typesafe4s.{Entry, Noul, TypesafeClient}
import typesafe4s.client.TypesafeConfig
import zio.*

object Quickstart extends ZIOAppDefault {
  def run =
    ZIO.scoped {
      for {
        config <- ZIO.fromEither(TypesafeConfig.resolve()) // reads TYPESAFE_API_KEY
        client <- TypesafeClient.scoped(config)
        eval   <- client.systemOneDynamic("The battery died after one day.")(
                    List("sentiment" -> Noul(Entry.text("Does this review express frustration?")))
                  )
        _      <- Console.printLine(eval.answers.answers).orDie
      } yield ()
    }
}
```

Runnable examples — each works without a key by falling back to a stub exchange:

```bash
sbt "examplesZio3_8_4/runMain typesafe4s.examples.Quickstart"
sbt "examplesCe3_8_4/runMain typesafe4s.examples.ConfidenceRouting"   # confidence-gated routing
# and likewise for examplesOx3_8_4 / examplesKyo3_8_4 / examplesPekko3_8_4
# (each row also has a `3_9_0` cell — see "Scala versions" below)
```

## Live tests

The parity suite includes credential-gated live tests: with `TYPESAFE_API_KEY` set they perform a
real exchange per row and reconcile the documented wire facts against the service; without it they
skip and the run stays green.

```bash
TYPESAFE_API_KEY=… sbt parityAll
```

## Documentation

- [Architecture analysis](docs/architecture-analysis.md) — design rationale and trade-offs
- [Project context](openspec/project.md) — module graph, stack, conventions
- [Implementation change](openspec/changes/add-typesafe4s-sdk/) — proposal, design, specs, tasks

## Development

Requires JDK 21+ (built and tested on JDK 26 with `-release 21`).

### Scala versions

Every module cross-compiles on **Scala 3.8.4** and **3.9.0** — matrix cells carry the version
suffix (`clientZio3_8_4`, `clientZio3_9_0`, …). Published artifacts are built on 3.8.4 only: TASTy
is forward-compatible, so the `…_3` jars serve callers on 3.8.x and later minors alike. The 3.9.0
cells compile and test in CI but are never published (both builds share the `_3` suffix and would
collide on identical coordinates).

```bash
sbt testUnit
```

Compiles and tests every backend row on both Scala versions, then runs the compat conformance suite.

```bash
sbt parityAll
```

Runs the cross-backend suite — written once against `kyo-compat`'s `CIO` — on all five published
rows (the 3.8.4 cells; CI additionally runs the 3.9.0 twins).
A test that passes on one row proves nothing about the others.

```bash
sbt conformanceCe
```

Runs the upstream `kyo-compat` conformance suite against the vendored Cats Effect binding
(see [`typesafe4s-compat-ce/README.md`](typesafe4s-compat-ce/README.md) for why it is vendored).

```bash
sbt fmt
```

Formats; `sbt check` verifies formatting without writing.

### Modules

| Module | Published as | Purpose |
|--------|--------------|---------|
| `typesafe4s-core` | `typesafe4s-core` | Pure, sans-IO. **Zero runtime dependencies** — a contract, not a preference. |
| `typesafe4s-compat-ce` | `typesafe4s-compat-ce` | Vendored `kyo.compat` Cats Effect binding (removed upstream at RC6). |
| `typesafe4s-client` | `typesafe4s-client-{zio,ce,ox,kyo,pekko}` | One source tree, cross-compiled per backend. The `future` row is compile-only and never published. |
| `typesafe4s-json-*` | `typesafe4s-{circe,zio-json,jsoniter,upickle}` | JSON adapters — each carries exactly its own library. |
| `integration-tests` | — | One parity suite, recompiled and run on every row. |
| `examples` | — | Per-row runnable examples; compiled in CI so they cannot rot. |

## License

Apache License 2.0 — see [LICENSE](LICENSE). The vendored `typesafe4s-compat-ce` sources originate from
[Kyo](https://github.com/getkyo/kyo), also Apache 2.0.
