package typesafe4s.client.typecontract

import scala.concurrent.duration.*

import kyo.compat.*

import typesafe4s.{RetryPolicy, TypesafeException}
import typesafe4s.client.{Client, LogLevel, Transport, TypesafeConfig}

// ============================================================================
// TYPED CONTRACT — spec: client-configuration (change: add-typesafe4s-sdk,
// spec 7/8)
//
// Gate 1 artifact — pending approval.
//
// This spec mostly EXTENDS surface that already exists: `TypesafeConfig` /
// `ApiKey` / `LogLevel` landed as spec-5/6 operands, `Client[F]` and the five
// facades as spec 6. The contract therefore declares the ADDITIONS in
// shadowed form (nested objects/traits — the promoted surface keeps the
// signatures shown here) plus the genuinely new `Model` product.
//
// SPEC AMENDMENTS this contract asks Gate 1 to approve:
//   A1 `Model` — the listing element. Concepts Introduced names only
//      TypesafeConfig + ApiKey (both pre-existing operands); the concept's
//      `listJudges => [judges: set of (ModelName, description, releaseDate)]`
//      needs a named product — a bare triple would be unreviewable.
//   A2 `TypesafeException.InvalidConfiguration` — a new sealed member for an
//      environment value that cannot be used (unparseable
//      TYPESAFE_LOG_LEVEL). Failing construction naming the variable beats a
//      silent fallback to the default — this project's strict-decode rule.
//   A3 `ApiKey.authorization` becomes `private[typesafe4s]` — it is PUBLIC
//      today and `key.authorization._2` yields `Bearer <key>` to any caller:
//      a direct breach of "the secret is never shown". Also fixes
//      `HttpRequest.toString` (prints the Bearer pair verbatim today) and
//      `TypesafeConfig.toString` (prints caller headers verbatim).
//   A4 `Transport.default` becomes a `def` — today it is a shared `val`, so
//      every `of(config)` client would close the SAME HttpClient on release.
//      Each default-constructed client must own its transport.
//   A5 `GET /v1/models` body shape — CONFIRMED 2026-09-19 (task 12.3):
//      `{"models":[{"name","description","release_date", ...}]}`
//      (the assumed `{"data":[{"id","display_name","created_at"}]}` was
//      wrong on every key); strict decode surfaces a mismatch as
//      ResponseValidation.
// ============================================================================

private object ClientConfigurationTypeContract {

  // --------------------------------------------------------------------------
  // spec: client-configuration — Evaluation/listJudges element (amendment A1).
  // "each model is reported with its description and release date"
  // --------------------------------------------------------------------------
  final case class Model(name: String, description: String, releaseDate: String)

  // --------------------------------------------------------------------------
  // spec: client-configuration — Requirement: A setting is taken from the
  // argument, then the environment, then the default.
  //
  // Promotes onto `object TypesafeConfig`. ONE shared resolution order for
  // every setting (tier-2 obligation): argument > environment > default.
  // `environment` is the injectable seam — the oracle drives it and records
  // the consulted names (Scenario: The documented variables are the ones
  // consulted).
  // --------------------------------------------------------------------------
  object Resolve {

    // the documented environment variables — the ONLY names consulted
    val apiKeyEnv   = "TYPESAFE_API_KEY"
    val baseUrlEnv  = "TYPESAFE_BASE_URL"
    val modelEnv    = "TYPESAFE_DEFAULT_MODEL"
    val logLevelEnv = "TYPESAFE_LOG_LEVEL"

    // the documented defaults
    val defaultBaseUrl   = "https://api.typesafe.ai"
    val defaultModel     = "jev-latest"
    val defaultAllowance = 10.seconds
    val defaultLogLevel  = LogLevel.Info

    /**
      * Resolves a configuration: each setting from the explicit argument,
      * else the documented variable, else the documented default.
      *
      * Left is `MissingCredential` (no credential resolves — names the
      * variable to set) or `InvalidConfiguration` (an environment value
      * cannot be used — names the variable; amendment A2).
      *
      * A blank credential — argument or environment — is ABSENT, not
      * present: `ApiKey.of` refuses blank as a presence check, so a blank
      * `TYPESAFE_API_KEY` resolves to `MissingCredential`, never to a
      * credential. Non-secret settings keep verbatim orElse semantics —
      * present-but-empty is a value (Property: Resolution follows one
      * order).
      */
    def resolve(
      apiKey: Option[String] = None,
      baseUrl: Option[String] = None,
      model: Option[String] = None,
      logLevel: Option[String] = None,
      allowance: FiniteDuration = defaultAllowance,
      retryPolicy: RetryPolicy = RetryPolicy.default,
      headers: List[(String, String)] = Nil,
      environment: String => Option[String] = sys.env.get
    ): Either[TypesafeException, TypesafeConfig] = ???
  }

  // --------------------------------------------------------------------------
  // spec: client-configuration — `object LogLevel` gains env parsing.
  // Unparseable input yields None; resolve maps that to InvalidConfiguration
  // naming TYPESAFE_LOG_LEVEL — never a silent default.
  // --------------------------------------------------------------------------
  object LogLevelOps {
    def parse(text: String): Option[LogLevel] = ???
  }

  // --------------------------------------------------------------------------
  // spec: client-configuration — `trait Client[F]` additions.
  //
  //   def close: F[Unit]         — the explicit release for construction
  //     outside a scope (tier-1: "the operation is part of the client
  //     surface"). Derived in LoweredClient; ClientCio runs
  //     `transport.release`.
  //   def listModels: F[List[Model]] — Evaluation/listJudges. NO local
  //     judgement of a name: a configured model absent from the listing is
  //     still sent as given (Scenario: A model the listing does not name).
  // --------------------------------------------------------------------------
  trait ClientOps[F[_]] {
    def close: F[Unit]
    def listModels: F[List[Model]]
  }

  // --------------------------------------------------------------------------
  // spec: client-configuration — `trait Transport` SPI addition.
  //
  //   def release: CIO[Unit] = CIO.unit — releases what the transport holds.
  //   The default keeps the SPI one-method: a caller transport holding
  //   nothing releasable implements `exchange` alone. `JdkHttpTransport()`
  //   OWNS its HttpClient → release closes it; `withClient(c)` is
  //   caller-owned → release is a no-op (documented on the method).
  // --------------------------------------------------------------------------
  trait TransportOps {
    def release: CIO[Unit]
  }

  // --------------------------------------------------------------------------
  // spec: client-configuration — `object Client` carrier helper: scoped
  // construction over the carrier, the anchor the shared parity suite drives
  // and each row's idiom lowers.
  // --------------------------------------------------------------------------
  object Scoped {

    /**
      * `acquireReleaseWith(acquire = cio(config, transport))(release =
      * _.close)(use)` — the release runs on success, failure and
      * abandonment of `use`, on every row the carrier runs on.
      */
    def apply[A](config: TypesafeConfig, transport: Transport)(
      use: Client[CIO] => CIO[A]
    ): CIO[A] = ???
  }

  // --------------------------------------------------------------------------
  // spec: client-configuration — redaction surface (Credential/render total).
  //
  //   ApiKey (core): `authorization` extension → private[typesafe4s]
  //     (amendment A3); new `private[typesafe4s] extension redact(text:
  //     String): String` — replaces the secret's occurrences with the
  //     marker; `private[typesafe4s] val redaction` — the marker constant.
  //   HttpRequest (core): `override def toString` — the authorization
  //     pair's value renders as the marker (it prints `Bearer <key>`
  //     verbatim today).
  //   TypesafeConfig: `override def toString` — `apiKey.redact` applied to
  //     the whole render, so a caller header holding the raw key is still
  //     masked.
  //   TypesafeException.fromResponse (core) gains
  //     `redact: String => String = identity`, applied to service-derived
  //     detail: a refusal body echoing the key cannot leak it through
  //     getMessage. `Exchange` threads `config.apiKey.redact` through —
  //     including the InternalServer branch for 1xx/3xx.
  // --------------------------------------------------------------------------

  // --------------------------------------------------------------------------
  // spec: client-configuration — scoped construction, each row's idiom.
  // Shapes differ per row — declared in the per-row
  // ScopedConstructionTypeContract files:
  //   zio   def scoped(config[, transport]): ZIO[Scope, TypesafeException, TypesafeClient]
  //   ce    def resource(config[, transport]): Resource[IO, TypesafeClient]
  //   ox    def scoped(config[, transport])(using Ox): TypesafeClient   — addFinalizer
  //   kyo   def scoped(config[, transport]): TypesafeClient < (Scope & Sync)
  //   pekko def use[A](config[, transport])(body: TypesafeClient => Future[A])(using ExecutionContext): Future[A]
  // ApiSurface[F] is UNCHANGED — the idioms have no common type.
  // --------------------------------------------------------------------------

  // --------------------------------------------------------------------------
  // spec: client-configuration — COMPILE-NEGATIVE OBLIGATIONS (discharged by
  // `outside`-package suites; `compileErrors`, not assertDoesNotCompile).
  //   * read of the secret's characters — `key.value`, `key.authorization`
  //     (holds once A3 lands).
  //   * client construction written in source with no resolvable credential —
  //     `TypesafeClient.of()` has no zero-arg overload; a `TypesafeConfig`
  //     literal cannot omit the required `apiKey`.
  // --------------------------------------------------------------------------

  // --------------------------------------------------------------------------
  // spec: client-configuration — PROPERTY / GENERATOR OBLIGATIONS.
  //   P1 resolution-follows-one-order (genResolution, constructive):
  //      per setting, draw present/absent independently for argument and
  //      environment incl. present-but-empty; resolved ==
  //      arg.orElse(env).getOrElse(default).
  //   P2 no-rendering-contains-the-secret (genSecretInContext): contexts
  //      enumerated — bare config, config inside a larger structure, a
  //      logged header set (HttpRequest's render), each failure member, and
  //      failures whose service-derived detail echoes the secret.
  // --------------------------------------------------------------------------
}
