package typesafe4s

import scala.concurrent.duration.{Duration, FiniteDuration}

// spec: effect-portability — the ask result product.
// The Evaluation concept names what an ask returns beyond the answers
// themselves: the model that answered (judge), the token cost, and the
// service's own trace. `answers` is the AnswerSet on the dynamic surface
// and the `AnswerOf`-typed named tuple on the typed one — `map` carries the
// metadata across the positional assembly untouched.
//
// `attempts` is how many exchanges the call made (1 when the first one
// succeeded, 3 after two retries) and `elapsed` the time the call spent from
// its first attempt to its answer, waits between retries included, read on
// the client's clock.
final case class Evaluation[A](
  answers: A,
  model: String,
  usage: Usage,
  requestId: Option[RequestId],
  attempts: Int = 1,
  elapsed: FiniteDuration = Duration.Zero
) {
  def map[B](f: A => B): Evaluation[B] = copy(answers = f(answers))
}

object Evaluation {

  /**
    * Merges the evaluations of one batched call (`systemOneBatched`) into
    * the evaluation of the whole question set: the union of the answers,
    * the summed usage, and the model that answered them.
    *
    * The request ID is kept only for a single evaluation — a merged result
    * came from several exchanges, whose IDs stay on the inputs. The attempts
    * are summed; the elapsed time is the longest input's, because the
    * batches of one call run concurrently.
    *
    * Left is `InvalidQuestion` when there is nothing to combine, when a name
    * is answered by more than one evaluation (never silently overwritten),
    * or when the evaluations were answered by different models.
    */
  def combine(evaluations: Iterable[Evaluation[AnswerSet]]): Either[TypesafeException, Evaluation[AnswerSet]] =
    evaluations.toList match {
      case Nil                => Left(TypesafeException.InvalidQuestion(None, "there are no evaluations to combine"))
      case single :: Nil      => Right(single)
      case all @ (first :: _) =>
        val models = all.map(_.model).distinct
        if (models.size > 1)
          Left(TypesafeException.InvalidQuestion(None, s"the evaluations were answered by different models: ${models.mkString(", ")}"))
        else {
          val merged = all.foldLeft[Either[TypesafeException, Map[String, Answer]]](Right(Map.empty)) { (acc, e) =>
            acc.flatMap { answers =>
              e.answers.answers.keys.find(answers.contains) match {
                case Some(name) => Left(TypesafeException.InvalidQuestion(Some(name), "answered by more than one evaluation"))
                case None       => Right(answers ++ e.answers.answers)
              }
            }
          }
          merged.map { answers =>
            val usage = all.map(_.usage).foldLeft(Usage.empty)(_ + _)
            Evaluation(
              AnswerSet(answers, first.model, usage, None),
              first.model,
              usage,
              None,
              all.map(_.attempts).sum,
              all.map(_.elapsed).max
            )
          }
        }
    }
}
