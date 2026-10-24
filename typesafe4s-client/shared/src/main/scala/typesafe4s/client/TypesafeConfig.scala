package typesafe4s.client

import java.net.URI

import scala.concurrent.duration.*
import scala.util.Try

import typesafe4s.{ApiKey, RetryPolicy, TypesafeException}

// ============================================================================
// spec: effect-portability — Implementation Anchors: `TypesafeConfig`
//
// The operand every client construction takes. This is the operand shape
// only: how a config is produced (env/resource resolution, redaction,
// lifecycle) belongs to spec 7, not here.
// ============================================================================
final case class TypesafeConfig(
  apiKey: ApiKey,
  baseUrl: String,
  model: String,
  allowance: FiniteDuration,
  retryPolicy: RetryPolicy,
  headers: List[(String, String)],
  logLevel: LogLevel,
  // #48: a cap on this client's request rate; None sends without one
  rateLimit: Option[RequestRateLimit] = None
) {

  /**
    * The only string form — the secret never appears (spec:
    * client-configuration — a rendering of a configuration, alone or
    * inside a larger structure, replaces the secret with a marker while
    * the non-secret settings stay readable). The WHOLE render is
    * scrubbed: a configured header or setting that happens to contain
    * the secret's characters is covered too.
    */
  override def toString: String =
    apiKey.redact(
      s"TypesafeConfig($apiKey,$baseUrl,$model,$allowance,$retryPolicy,$headers,$logLevel)"
    )
}

// ============================================================================
// spec: client-configuration — Credential/resolve + the documented variables
// and defaults (concepts/credential.md value domains). ONE resolution order
// for every setting (tier-2 obligation): argument > environment > default.
// ============================================================================
object TypesafeConfig {

  // the documented environment variables — the ONLY names `resolve` consults
  val apiKeyEnv   = "TYPESAFE_API_KEY"
  val baseUrlEnv  = "TYPESAFE_BASE_URL"
  val modelEnv    = "TYPESAFE_DEFAULT_MODEL"
  val logLevelEnv = "TYPESAFE_LOG_LEVEL"
  val timeoutEnv  = "TYPESAFE_TIMEOUT"

  // the documented defaults
  val defaultBaseUrl   = "https://api.typesafe.ai"
  val defaultModel     = "jev-latest"
  val defaultAllowance = 10.seconds
  val defaultLogLevel  = LogLevel.Info

  /**
    * Resolves a configuration: each setting from the explicit argument, else
    * the documented variable, else the documented default.
    *
    * Left is `MissingCredential` (no credential resolves — names the
    * variable to set) or `InvalidConfiguration` (an environment value cannot
    * be used — names the variable).
    *
    * `environment` is the injectable seam — production reads `sys.env`, the
    * oracle records the consulted names.
    *
    * `baseUrl` must be an absolute `https` URL with a host and no query,
    * fragment or user info; plain `http` is accepted only for a loopback
    * host (`localhost`, `127.x.x.x`, `[::1]`), for local stubs, so the
    * credential never travels in plaintext. Trailing slashes are removed,
    * so `https://api.typesafe.ai/` resolves to `https://api.typesafe.ai`.
    * Anything else is `InvalidConfiguration(TYPESAFE_BASE_URL, ...)`.
    *
    * `allowance` bounds each attempt. From the environment it is read from
    * `TYPESAFE_TIMEOUT` in `scala.concurrent.duration.Duration` syntax
    * (`30s`, `1500ms`, `2 minutes`); from either source it must be a finite,
    * positive duration, else `InvalidConfiguration(TYPESAFE_TIMEOUT, ...)`.
    */
  def resolve(
    apiKey: Option[String] = None,
    baseUrl: Option[String] = None,
    model: Option[String] = None,
    logLevel: Option[String] = None,
    allowance: Option[FiniteDuration] = None,
    retryPolicy: RetryPolicy = RetryPolicy.default,
    headers: List[(String, String)] = Nil,
    environment: String => Option[String] = sys.env.get
  ): Either[TypesafeException, TypesafeConfig] = {
    // ONE order, one shape for every setting: argument, else the
    // documented variable, else the documented default. The credential
    // alone is presence-checked (a blank string can never construct an
    // ApiKey, so it resolves as absent); other settings are verbatim —
    // a present-but-empty value is a value. The credential resolves
    // first: its absence is reported before any other invalid value.
    import typesafe4s.TypesafeException.{InvalidConfiguration, MissingCredential}
    val keyE  = apiKey
      .filter(!_.isBlank)
      .orElse(environment(apiKeyEnv).filter(!_.isBlank))
      .map(ApiKey.of)
      .toRight(MissingCredential(apiKeyEnv): TypesafeException)
    val level = logLevel.orElse(environment(logLevelEnv))
    val limit = allowance.map(Right(_)).orElse(environment(timeoutEnv).map(raw => parseAllowance(raw).toRight(raw)))
    for {
      key    <- keyE
      parsed <- level match {
                  case None      => Right(defaultLogLevel)
                  case Some(raw) =>
                    LogLevel
                      .parse(raw)
                      .toRight(
                        // the value is echoed into the failure's detail —
                        // scrub it with the resolved credential: an env
                        // value that happens to contain the secret's
                        // characters must not render them
                        InvalidConfiguration(logLevelEnv, s"'${key.redact(raw)}' is not a log level"): TypesafeException
                      )
                }
      url    <- validBaseUrl(baseUrl.orElse(environment(baseUrlEnv)).getOrElse(defaultBaseUrl)).left.map { why =>
                  InvalidConfiguration(baseUrlEnv, key.redact(why)): TypesafeException
                }
      within <- limit match {
                  case None                                => Right(defaultAllowance)
                  case Some(Right(d)) if d > Duration.Zero => Right(d)
                  case Some(Right(d))                      =>
                    Left(InvalidConfiguration(timeoutEnv, s"the per-attempt allowance must be positive, got $d"): TypesafeException)
                  case Some(Left(raw))                     =>
                    Left(
                      InvalidConfiguration(timeoutEnv, s"'${key.redact(raw)}' is not a positive duration such as 30s or 1500ms"): TypesafeException
                    )
                }
    } yield TypesafeConfig(
      apiKey = key,
      baseUrl = url,
      model = model.orElse(environment(modelEnv)).getOrElse(defaultModel),
      allowance = within,
      retryPolicy = retryPolicy,
      headers = headers,
      logLevel = parsed
    )
  }

  // Right is the URL with its trailing slashes removed; Left says why it
  // cannot be used (the caller scrubs it — it may echo the raw value)
  private def validBaseUrl(raw: String): Either[String, String] = {
    val trimmed = raw.trim
    Try(new URI(trimmed)).toOption
      .filter(u => u.isAbsolute && !u.isOpaque && u.getHost != null)
      .toRight(s"'$raw' is not an absolute URL such as https://api.typesafe.ai")
      .flatMap { u =>
        val scheme = u.getScheme.toLowerCase
        if (u.getRawQuery != null || u.getRawFragment != null) Left(s"'$raw' must not carry a query or a fragment")
        else if (u.getRawUserInfo != null) Left("the URL must not carry user info; the credential is sent as a header")
        else if (scheme == "https" || (scheme == "http" && isLoopback(u.getHost))) Right(trimmed.reverse.dropWhile(_ == '/').reverse)
        else if (scheme == "http") Left(s"'$raw' would send the credential in plaintext; use https (http is allowed only for localhost)")
        else Left(s"'$raw' is not an http(s) URL")
      }
  }

  private def isLoopback(host: String): Boolean = {
    val h = host.toLowerCase
    h == "localhost" || h == "[::1]" || h.split('.').toList.match {
      case "127" :: rest => rest.size == 3 && rest.forall(o => o.nonEmpty && o.forall(_.isDigit) && o.toInt <= 255)
      case _             => false
    }
  }

  // an environment value is a finite, positive Duration string; anything
  // else (unparseable, infinite, zero, negative) is reported by `resolve`
  private def parseAllowance(raw: String): Option[FiniteDuration] =
    Try(Duration(raw.trim)).toOption.collect { case d: FiniteDuration if d > Duration.Zero => d }
}
