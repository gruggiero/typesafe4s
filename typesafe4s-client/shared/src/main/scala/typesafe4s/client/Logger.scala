package typesafe4s.client

/**
  * Where the client's log lines go (#20). The client logs at the configured `LogLevel` and above (`TYPESAFE_LOG_LEVEL`):
  *   - Debug: each attempt, by method, path and attempt number.
  *   - Info: each retry and the wait before it.
  *   - Warning: each failure surfaced to the caller.
  *
  * No line carries the API key or a request or response body. The default, `Logger.noop`, writes nothing, so the client adds no output
  * and no logging dependency until a logger is chosen: `config.copy(logger = Logger.system)` writes through `java.lang.System.Logger`,
  * which a JUL, Log4j or SLF4J bridge can route anywhere.
  */
trait Logger {

  /**
    * Writes `message` at `level`. Called only for levels the configuration enables; `message` is built only when called.
    */
  def log(level: LogLevel, message: => String): Unit
}

object Logger {

  /**
    * The default: writes nothing.
    */
  val noop: Logger = new Logger {
    def log(level: LogLevel, message: => String): Unit = ()
  }

  /**
    * Writes through `java.lang.System.Logger` under the name `typesafe4s`.
    */
  def system: Logger = new Logger {
    private val underlying                             = System.getLogger("typesafe4s")
    def log(level: LogLevel, message: => String): Unit = {
      val jdk = level match {
        case LogLevel.Debug   => System.Logger.Level.DEBUG
        case LogLevel.Info    => System.Logger.Level.INFO
        case LogLevel.Warning => System.Logger.Level.WARNING
        case LogLevel.Error   => System.Logger.Level.ERROR
      }
      if (underlying.isLoggable(jdk)) underlying.log(jdk, message)
    }
  }
}
