package typesafe4s

// ============================================================================
// spec: wire-codec — operand: the decoded response product
// `requestId` is populated by the caller-supplied trace — the same value the
// ResponseValidation failure side reports.
// ============================================================================
final case class AnswerSet(answers: Map[String, Answer], model: String, usage: Usage, requestId: Option[RequestId]) {
  def names: Set[String] = answers.keySet

  // spec: question-model — Requirement: Question sets built at run time are
  // equally supported. Kind-indexed lookups over the decoded answers: an
  // unasked name or a wrong-kind read is a failure value (MissingAnswer),
  // never a raise (Scenarios: reading a name that was never asked; reading
  // an answer as the wrong kind — one failure channel per lookup, D6)
  def noul(key: String): Either[TypesafeException.MissingAnswer, NoulAnswer] =
    answers.get(key) match {
      case Some(a: NoulAnswer) => Right(a)
      case _                   => Left(TypesafeException.MissingAnswer(key))
    }

  def choice(key: String): Either[TypesafeException.MissingAnswer, ChoiceAnswer[?]] =
    answers.get(key) match {
      case Some(a: ChoiceAnswer[?]) => Right(a)
      case _                        => Left(TypesafeException.MissingAnswer(key))
    }

  def score(key: String): Either[TypesafeException.MissingAnswer, ScoreAnswer[Int]] =
    answers.get(key) match {
      case Some(a: ScoreAnswer[?]) => Right(a)
      case _                       => Left(TypesafeException.MissingAnswer(key))
    }
}
