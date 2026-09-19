package typesafe4s

// ============================================================================
// spec: wire-codec — Concepts Used: Evaluation/ask — the decoder's answer
// value type. Sealed: every decoded answer is one of the three kinds the wire
// marker can name; spec 3's kind lookups match on it exhaustively.
// ============================================================================
sealed trait Answer

// the probability is both the answer and the certainty — NO confidence member
final case class NoulAnswer(noul: Double) extends Answer

// probabilities are keyed by the OPTION (O), decoded through the question's
// option mapping; confidence is the server-reported certainty (opaque value)
final case class ChoiceAnswer[O](choice: O, probabilities: Map[O, Double], confidence: Double) extends Answer

// legend and probabilities are keyed by level NUMBER (zero-based on the wire);
// legend values are entry-shaped — a non-entry legend value is a fit failure
// spec: question-model — L is covariant: it is a phantom index (no member
// mentions it), so ScoreAnswer[3] <: ScoreAnswer[Int] and the dynamic surface
// can read an answer whose declared level count is not statically known
final case class ScoreAnswer[+L <: Int](score: Double, legend: Map[Int, Entry], probabilities: Map[Int, Double], confidence: Double) extends Answer
