package typesafe4s

// ============================================================================
// spec: wire-codec — Concepts Introduced: Usage
// Token counts; each independently absent — a response may report neither.
// ============================================================================
final case class Usage(inputTokens: Option[Long], outputTokens: Option[Long]) {

  /**
    * The counts of two exchanges added together. A count is absent only when
    * both sides lack it; a count one side lacks contributes nothing.
    */
  def +(that: Usage): Usage =
    Usage(Usage.sum(inputTokens, that.inputTokens), Usage.sum(outputTokens, that.outputTokens))
}

object Usage {

  /**
    * No exchange: both counts absent — the identity of `+`.
    */
  val empty: Usage = Usage(None, None)

  private def sum(a: Option[Long], b: Option[Long]): Option[Long] =
    if (a.isEmpty && b.isEmpty) None else Some(a.getOrElse(0L) + b.getOrElse(0L))
}
