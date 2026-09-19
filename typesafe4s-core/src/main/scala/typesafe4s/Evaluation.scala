package typesafe4s

// spec: effect-portability — the ask result product.
// The Evaluation concept names what an ask returns beyond the answers
// themselves: the model that answered (judge), the token cost, and the
// service's own trace. `answers` is the AnswerSet on the dynamic surface
// and the `AnswerOf`-typed named tuple on the typed one — `map` carries the
// metadata across the positional assembly untouched.
final case class Evaluation[A](
  answers: A,
  model: String,
  usage: Usage,
  requestId: Option[RequestId]
) {
  def map[B](f: A => B): Evaluation[B] = copy(answers = f(answers))
}
