package typesafe4s.client

// spec: effect-portability — the logging level carried on TypesafeConfig.
enum LogLevel {
  case Debug, Info, Warning, Error
}

object LogLevel {

  /**
    * Parses a `TYPESAFE_LOG_LEVEL` value — case-insensitive `debug`, `info`,
    * `warn`/`warning`, `error`. An unparseable value yields None; resolution
    * maps that to `InvalidConfiguration` naming the variable, never a silent
    * default (spec: client-configuration).
    */
  def parse(text: String): Option[LogLevel] =
    // Locale.ROOT — the default locale turns 'I' into 'ı' under tr/az and
    // a documented INFO would fail to parse
    text.trim.toLowerCase(java.util.Locale.ROOT) match {
      case "debug"            => Some(Debug)
      case "info"             => Some(Info)
      case "warn" | "warning" => Some(Warning)
      case "error"            => Some(Error)
      case _                  => None
    }
}
