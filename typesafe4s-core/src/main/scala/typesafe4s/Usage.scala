package typesafe4s

// ============================================================================
// spec: wire-codec — Concepts Introduced: Usage
// Token counts; each independently absent — a response may report neither.
// ============================================================================
final case class Usage(inputTokens: Option[Long], outputTokens: Option[Long])
