package typesafe4s.client

import scala.concurrent.duration.*

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
  logLevel: LogLevel
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
    } yield TypesafeConfig(
      apiKey = key,
      baseUrl = baseUrl.orElse(environment(baseUrlEnv)).getOrElse(defaultBaseUrl),
      model = model.orElse(environment(modelEnv)).getOrElse(defaultModel),
      allowance = allowance,
      retryPolicy = retryPolicy,
      headers = headers,
      logLevel = parsed
    )
  }
}
