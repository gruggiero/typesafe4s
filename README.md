# typesafe4s

[![CI](https://github.com/gruggiero/typesafe4s/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/gruggiero/typesafe4s/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.gruggiero/typesafe4s-core_3)](https://central.sonatype.com/namespace/io.github.gruggiero)
[![Scala 3](https://img.shields.io/badge/scala-3.8%2B-dc322f?logo=scala)](https://www.scala-lang.org/)
[![JDK 21+](https://img.shields.io/badge/JDK-21%2B-437291)](#development)
[![License](https://img.shields.io/github/license/gruggiero/typesafe4s)](LICENSE)

A Scala 3 SDK for the [TypeSafe](https://docs.typesafe.ai) System One API (`jev` model family):
send state and typed questions, get typed answers with calibrated probabilities.

> **Status: 0.1 development.** The full pipeline — error model, wire codec, question model, retry,
> transport, per-backend facades, batching and JSON adapters — is implemented and verified; the API
> may still shift before the first tagged release.

> **Not affiliated with Typesafe Inc. (now Lightbend).** "TypeSafe" here means the AI platform at
> [typesafe.ai](https://docs.typesafe.ai), not the company behind Akka, Play, or `typesafe-config`.

typesafe4s is part of the [LLM4S Foundation](https://llm4s.org/foundation/). See
[Maintainers](#maintainers) and [Community](#community) to meet the team.

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

## Compatibility

Versions follow [early semantic versioning](https://www.scala-lang.org/blog/2021/02/16/preventing-version-conflicts-with-versionscheme.html),
and the published POMs declare it (`versionScheme`), so sbt and other build tools can report a real conflict:

- a **patch** release (0.1.0 → 0.1.1) is binary compatible: fixes only, safe to upgrade;
- a **minor** release (0.1 → 0.2) may break compatibility while the version is 0.x.

`sbt check` runs [MiMa](https://github.com/lightbend-labs/mima) on every published artifact against
the previous release, so a patch cannot break code compiled against it.

### Other build tools

The artifacts are Scala 3 libraries, so outside sbt the name carries the `_3` suffix (sbt's `%%`
adds it for you). Swap `-zio` for `-ce`, `-ox`, `-kyo` or `-pekko` to pick your backend; the JSON
adapters follow the same pattern (`typesafe4s-circe_3` and so on).

Maven:

```xml
<dependency>
  <groupId>io.github.gruggiero</groupId>
  <artifactId>typesafe4s-client-zio_3</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle (Kotlin DSL; the Groovy DSL takes the same string):

```kotlin
implementation("io.github.gruggiero:typesafe4s-client-zio_3:0.1.0")
```

Mill (1.x; 0.12 and earlier spell it `ivy"..."`):

```scala
def mvnDeps = Seq(mvn"io.github.gruggiero::typesafe4s-client-zio:0.1.0")
```

scala-cli, in a source file:

```scala
//> using dep io.github.gruggiero::typesafe4s-client-zio:0.1.0
```

To try the SDK in one file with no build, run
[`examples/scala-cli/quickstart.scala`](examples/scala-cli/quickstart.scala):
`scala-cli run examples/scala-cli/quickstart.scala`. Without `TYPESAFE_API_KEY` it answers from a
canned response, so it runs anywhere.

From the next release, `typesafe4s-play-json` adds the same adapter for
[play-json](https://github.com/playframework/play-json), for Play Framework applications
(`import typesafe4s.playjson.JsonEntry`).

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

## Behind a corporate proxy

The default transport is the JDK's `HttpClient`. `JdkHttpTransport.configured` builds one with
your settings, and the client closes it when it is closed, like the default:

```scala
import java.net.{InetSocketAddress, ProxySelector}
import java.net.http.HttpClient
import scala.concurrent.duration.*
import typesafe4s.client.JdkHttpTransport

val transport = JdkHttpTransport.configured(
  proxy = Some(ProxySelector.of(InetSocketAddress("proxy.corp.example", 3128))),
  connectTimeout = Some(5.seconds),
  version = Some(HttpClient.Version.HTTP_1_1) // for a proxy that mishandles HTTP/2
)
// then: TypesafeClient.scoped(config, transport)
```

`proxy = Some(ProxySelector.getDefault)` follows the standard `-Dhttps.proxyHost` and
`-Dhttps.proxyPort` system properties instead. A proxy that asks for credentials takes
`authenticator = Some(...)`; note that the JDK refuses Basic authentication for HTTPS tunnels
unless `jdk.http.auth.tunneling.disabledSchemes` is cleared. For anything else, build the
`HttpClient` yourself and pass it to `JdkHttpTransport.withClient`, which leaves closing it to you.

## User-Agent

Every request names the SDK, its version, the backend and the Scala version, which helps the
service answer support questions:

```
User-Agent: typesafe4s/0.1.0 (zio; scala/3.8.4)
```

A `User-Agent` in the configured `headers` is kept, with the SDK's token after it
(`myapp/2.3 typesafe4s/0.1.0 (zio; scala/3.8.4)`).

### Large question sets

`systemOneBatched` splits a question set too large for one exchange into batches and streams one
`Evaluation` per batch. To get the evaluation of the whole set, collect the stream and merge it with
`Evaluation.combine`, which unions the answers (a name answered twice is an error), sums the usage and
keeps the model (ZIO shown; every row's stream collects the same way):

```scala
for {
  evals <- client.systemOneBatched(state)(questions).runCollect
  whole <- ZIO.fromEither(Evaluation.combine(evals))
} yield whole.answers
```

## Live tests

The parity suite includes credential-gated live tests: with `TYPESAFE_API_KEY` set they perform a
real exchange per row and reconcile the documented wire facts against the service; without it they
skip and the run stays green.

```bash
TYPESAFE_API_KEY=… sbt parityAll
```

## Documentation

- [API documentation](https://gruggiero.github.io/typesafe4s/) — Scaladoc for the core, every client row and the JSON adapters
- [Changelog](CHANGELOG.md) — what changed in each release
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
| `typesafe4s-json-*` | `typesafe4s-{circe,zio-json,jsoniter,upickle,play-json}` | JSON adapters — each carries exactly its own library. |
| `integration-tests` | — | One parity suite, recompiled and run on every row. |
| `examples` | — | Per-row runnable examples; compiled in CI so they cannot rot. |

## Security

Please report vulnerabilities privately, as described in [SECURITY.md](SECURITY.md), not in a public issue.

## Maintainers

- **Kannupriya Kalra** (maintainer) - [@kannupriyakalra](https://github.com/kannupriyakalra) ·
  [LinkedIn](https://www.linkedin.com/in/kannupriyakalra/) · [kannupriya@llm4s.org](mailto:kannupriya@llm4s.org)
- **Giovanni Ruggiero** (original creator) - [@gruggiero](https://github.com/gruggiero)

## Community

typesafe4s is a project of the **[LLM4S Foundation](https://llm4s.org/foundation/)**, a global Scala and AI
community that helps developers learn AI engineering by contributing to real open-source projects such as
[LLM4S](https://github.com/llm4s/llm4s), the AI framework for Scala, and typesafe4s.

### Contribute and grow: global internship opportunities

Contributing to typesafe4s is a practical way into AI engineering, and through the
[LLM4S Foundation](https://llm4s.org/foundation/) it can open doors to global internship opportunities:

- **Build a public track record.** Every merged pull request is real, reviewed AI engineering work in Scala that you
  can point to in applications and interviews.
- **Learn from mentors.** Maintainers review your changes and help you grow, and you can meet them and experienced
  Scala developers at the weekly Dev Hours.
- **Get noticed for internships.** The foundation connects active contributors with global internship and career
  pathways, and mentors programmes such as [Google Summer of Code](https://llm4s.org/gsoc/). Consistent, high-quality
  contributions are the best way to be considered.
- **Grow from first issue to bigger work.** Start with an [open issue](https://github.com/gruggiero/typesafe4s/issues),
  then take on bigger work here and in the main [LLM4S](https://github.com/llm4s/llm4s) repository.

### Meet the maintainers and community

We meet every **Sunday at 9:00 AM London time** for Dev Hours.

- **Dev Hours calendar:** [luma.com/calendar/cal-Zd9BLb5jbZewxLA](https://luma.com/calendar/cal-Zd9BLb5jbZewxLA). Join the event for the date you want to attend.
- **Add every Dev Hour to your calendar:** open the
  [LLM4S Dev Hour weekly calendar link](https://api2.luma.com/ics/get?entity=calendar&id=cal-Zd9BLb5jbZewxLA). It adds
  all the events for 2026 to your Google Calendar, so you don't miss a Dev Hour.

### Links

- **Global AI Discord community** (~400 members): [discord.com/invite/DjPMufnhG6](https://discord.com/invite/DjPMufnhG6).
  Please feel free to invite anyone who may benefit from joining our community.
- **Website and documentation:** [llm4s.org](https://llm4s.org/)
- **GitHub organization:** [github.com/llm4s](https://github.com/llm4s)
- **Main repository:** [github.com/llm4s/llm4s](https://github.com/llm4s/llm4s)

### Support the project

- **Open Collective:** [opencollective.com/llm4s](https://opencollective.com/llm4s). If you find LLM4S useful, even a
  $1 donation would really help support the project.
- If your company is looking for an AI framework, please help us connect.

## License

Apache License 2.0 — see [LICENSE](LICENSE). The vendored `typesafe4s-compat-ce` sources originate from
[Kyo](https://github.com/getkyo/kyo), also Apache 2.0.
