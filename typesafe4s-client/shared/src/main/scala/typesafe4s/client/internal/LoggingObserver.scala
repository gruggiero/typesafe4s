package typesafe4s.client.internal

import scala.concurrent.duration.FiniteDuration

import typesafe4s.{ApiKey, HttpMethod, TypesafeException}
import typesafe4s.client.{Logger, LogLevel, Observer, TypesafeConfig}

// ============================================================================
// #20 — TYPESAFE_LOG_LEVEL takes effect: the configured logger receives
// the pipeline's events at their levels, filtered by the configured level.
// Built on the #28 Observer, so it inherits its guarantees (inline, cannot
// fail a call). Every line is scrubbed with the credential, and no body is
// ever logged.
// ============================================================================
final private[typesafe4s] class LoggingObserver(logger: Logger, threshold: LogLevel, key: ApiKey) extends Observer {

  private def emit(level: LogLevel)(message: => String): Unit =
    if (level.ordinal >= threshold.ordinal) logger.log(level, key.redact(message))

  override def onAttempt(method: HttpMethod, path: String, attempt: Int): Unit =
    emit(LogLevel.Debug)(s"typesafe4s: ${method.toString.toUpperCase} $path, attempt $attempt")

  override def onRetry(attempt: Int, wait: FiniteDuration, failure: TypesafeException): Unit =
    emit(LogLevel.Info)(s"typesafe4s: attempt $attempt failed, retrying in ${wait.toMillis} ms: ${failure.getMessage}")

  override def onFailure(method: HttpMethod, path: String, failure: Throwable): Unit =
    emit(LogLevel.Warning)(s"typesafe4s: ${method.toString.toUpperCase} $path failed: ${failure.getMessage}")
}

private[typesafe4s] object LoggingObserver {

  /**
    * The observer the pipeline calls for `config`: the configured observer, followed by the logging one when a logger is set.
    */
  def of(config: TypesafeConfig): Observer =
    if (config.logger eq Logger.noop) config.observer
    else Observer.both(config.observer, new LoggingObserver(config.logger, config.logLevel, config.apiKey))
}
