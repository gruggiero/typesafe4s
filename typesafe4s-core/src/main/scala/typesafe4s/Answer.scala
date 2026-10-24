package typesafe4s

// ============================================================================
// spec: wire-codec — Concepts Used: Evaluation/ask — the decoder's answer
// value type. Sealed: every decoded answer is one of the three kinds the wire
// marker can name; spec 3's kind lookups match on it exhaustively.
// ============================================================================
sealed trait Answer

object Answer {

  // every confidence helper takes a probability threshold — a value outside
  // [0, 1] (or NaN) can never be met or always is, so it is a caller error
  private[typesafe4s] def requireThreshold(threshold: Double): Unit =
    require(threshold >= 0.0 && threshold <= 1.0, s"a threshold is a probability in [0, 1], got $threshold")
}

// the probability is both the answer and the certainty — NO confidence member
final case class NoulAnswer(noul: Double) extends Answer {

  /**
    * True when the statement holds with at least `threshold` probability.
    * Throws `IllegalArgumentException` when `threshold` is outside [0, 1].
    */
  def isTrue(threshold: Double): Boolean = {
    Answer.requireThreshold(threshold)
    noul >= threshold
  }

  /**
    * True when the statement fails with at least `threshold` probability
    * (`1 - noul >= threshold`). Throws `IllegalArgumentException` when
    * `threshold` is outside [0, 1].
    */
  def isFalse(threshold: Double): Boolean = {
    Answer.requireThreshold(threshold)
    1.0 - noul >= threshold
  }
}

// probabilities are keyed by the OPTION (O), decoded through the question's
// option mapping; confidence is the server-reported certainty (opaque value)
final case class ChoiceAnswer[O](choice: O, probabilities: Map[O, Double], confidence: Double) extends Answer {

  /**
    * The options with their probabilities, most likely first. Options with
    * equal probability follow one another in an unspecified order.
    */
  def ranked: List[(O, Double)] = probabilities.toList.sortBy(kv => -kv._2)

  /**
    * The reported choice when the service's confidence is at least
    * `threshold`, else None. Throws `IllegalArgumentException` when
    * `threshold` is outside [0, 1].
    */
  def confidentAt(threshold: Double): Option[O] = {
    Answer.requireThreshold(threshold)
    Option.when(confidence >= threshold)(choice)
  }
}

// legend and probabilities are keyed by level NUMBER (zero-based on the wire);
// legend values are entry-shaped — a non-entry legend value is a fit failure
// spec: question-model — L is covariant: it is a phantom index (no member
// mentions it), so ScoreAnswer[3] <: ScoreAnswer[Int] and the dynamic surface
// can read an answer whose declared level count is not statically known
final case class ScoreAnswer[+L <: Int](score: Double, legend: Map[Int, Entry], probabilities: Map[Int, Double], confidence: Double) extends Answer {

  /**
    * The most likely level; on a tie, the lowest level. None when the service
    * reported no level probabilities.
    */
  def level: Option[Int] = probabilities.toList.sortBy(kv => (-kv._2, kv._1)).headOption.map(_._1)

  /**
    * The legend entry of the most likely level, when it has one.
    */
  def label: Option[Entry] = level.flatMap(legend.get)

  /**
    * The most likely level when the service's confidence is at least
    * `threshold`, else None. Throws `IllegalArgumentException` when
    * `threshold` is outside [0, 1].
    */
  def confidentAt(threshold: Double): Option[Int] = {
    Answer.requireThreshold(threshold)
    level.filter(_ => confidence >= threshold)
  }
}
