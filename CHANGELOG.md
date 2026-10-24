# Changelog

All notable changes to typesafe4s are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[early semantic versioning](https://www.scala-lang.org/blog/2021/02/16/preventing-version-conflicts-with-versionscheme.html):
while the version is 0.x, a minor release (0.1 to 0.2) may break compatibility and a patch release
(0.1.0 to 0.1.1) does not.

Record each user-visible change under **Unreleased** in the same pull request that makes it, in
one of: Added, Changed, Deprecated, Removed, Fixed, Security. At a release, the maintainer renames
**Unreleased** to the version and date.

## [Unreleased]

### Added

- `TypesafeConfig.rateLimit` caps a client's request rate (`RequestRateLimit(perSecond, burst)`). Every attempt
  takes a permit, and waiting for one counts against the retry policy's total bound. It is off by default (#48).

## [0.1.0] - 2026-09-19

First release. A Scala 3 SDK for the TypeSafe System One API (`jev` models): send a state and
typed questions, get typed answers with calibrated probabilities.

### Added

- `typesafe4s-core`: the pure, sans-IO core with no runtime dependencies. It holds the JSON AST,
  `Entry` and `StateEncoder`, the `Question[A]` model with derived `Choice` options, the request
  encoder and response decoder, the closed `TypesafeException` family, `RetryPolicy` and the
  token estimator.
- One client artifact per effect system, all from one source tree:
  `typesafe4s-client-zio`, `typesafe4s-client-ce`, `typesafe4s-client-ox`,
  `typesafe4s-client-kyo` and `typesafe4s-client-pekko`.
- Dynamic and typed (named-tuple) question sets, batching with a concurrency bound, and model
  listing.
- Retries with exponential backoff, jitter, a total time bound, and the service's `Retry-After`
  on rate limits; a per-attempt time allowance.
- Configuration from arguments, then `TYPESAFE_API_KEY`, `TYPESAFE_BASE_URL`,
  `TYPESAFE_DEFAULT_MODEL` and `TYPESAFE_LOG_LEVEL`, then defaults. The key never appears in
  any rendering of a configuration, request or failure.
- JSON adapters, each carrying only its own library: `typesafe4s-circe`, `typesafe4s-zio-json`,
  `typesafe4s-jsoniter` and `typesafe4s-upickle`.
- `typesafe4s-compat-ce`: the vendored kyo-compat Cats Effect binding.
- Runnable `Quickstart` and `ConfidenceRouting` examples for every backend, working offline
  against a stub exchange.

[Unreleased]: https://github.com/gruggiero/typesafe4s/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/gruggiero/typesafe4s/releases/tag/v0.1.0
