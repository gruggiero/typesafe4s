package typesafe4s

import scala.NamedTuple.NamedTuple
import scala.collection.immutable.ListMap
import scala.compiletime.{constValue, constValueTuple, error}

// ============================================================================
// spec: wire-codec — operand: the question set
// Non-empty by construction — the "at least one question" rule is a type
// fact: the public `apply` requires a first entry, and the private
// constructor (with its private generated apply) admits no empty ListMap.
// Entries keep insertion order.
// ============================================================================
final case class QuestionSet private (entries: ListMap[String, Question[?]]) {
  def names: Set[String] = entries.keySet
  def size: Int          = entries.size
}

object QuestionSet {

  def apply(first: (String, Question[?]), rest: (String, Question[?])*): QuestionSet =
    QuestionSet(ListMap.from(first +: rest))

  def fromEntries(entries: IterableOnce[(String, Question[?])]): Option[QuestionSet] = {
    val ms = ListMap.from(entries)
    if (ms.isEmpty) None else Some(QuestionSet(ms))
  }

  // spec: question-model — the runtime submission gate: a question set
  // assembled at run time is checked against the documented limits (a Choice
  // with more than 255 options, a Score outside 2..10 levels, an empty set)
  // BEFORE any request is formed; the failure names the offending question
  // (Scenario: A limit broken only at run time still fails safely)
  def validated(entries: IterableOnce[(String, Question[?])]): Either[TypesafeException.InvalidQuestion, QuestionSet] = {
    val ms = ListMap.from(entries)
    if (ms.isEmpty) Left(TypesafeException.InvalidQuestion(None, "an evaluation must ask at least one question"))
    else
      ms.iterator
        .flatMap { case (name, q) => limitViolation(q).map(d => TypesafeException.InvalidQuestion(Some(name), d)) }
        .nextOption() match {
        case Some(failure) => Left(failure)
        case None          => Right(QuestionSet(ms))
      }
  }

  // the documented limits, checked on values the type level could not see —
  // a raw `Choice.apply`/`Score.apply` can smuggle an over-limit shape past
  // the inline constructors
  private def limitViolation(q: Question[?]): Option[String] = q match {
    case c: Choice[?] if c.options.isEmpty    => Some("a Choice needs at least one option")
    case c: Choice[?] if c.options.size > 255 => Some(s"a Choice accepts at most 255 options; ${c.options.size} were supplied")
    case s: Score[?] if s.levels.size < 2     => Some(s"a Score needs at least two levels; ${s.levels.size} was supplied")
    case s: Score[?] if s.levels.size > 10    => Some(s"a Score accepts at most ten levels; ${s.levels.size} were supplied")
    // in-limit questions pass — spelled out per variant (not `case _`) so a
    // future Question variant is flagged exhaustively instead of passing
    case _: Choice[?] | _: Score[?] | _: Noul => None
  }

  // spec: question-model — Requirement: A question set written in source
  // yields a matching answer set. The wire keys come from the field names
  // (constValueTuple[N]) — a key is never written twice. An empty named tuple
  // is refused before the program runs (Scenario: A question set with
  // nothing in it); per-question limits re-check through `validated`.
  // stryker4s: mutations inside the constValue check can only produce a
  // compile error at expansion, never a runtime-killable mutant — the limit
  // is discharged by Ring 0 + compile-negatives, so it is suppressed here
  @SuppressWarnings(
    Array(
      "stryker4s.mutation.ConditionalExpression",
      "stryker4s.mutation.EqualityOperator",
      "stryker4s.mutation.BooleanLiteral",
      "stryker4s.mutation.StringLiteral"
    )
  )
  inline def fromNamedTuple[N <: Tuple, V <: Tuple](
    questions: NamedTuple[N, V]
  )(using ev: Tuple.Union[V] <:< Question[?]): Either[TypesafeException.InvalidQuestion, QuestionSet] =
    inline if constValue[Tuple.Size[N]] == 0 then error("an evaluation must ask at least one question")
    else {
      val names = constValueTuple[N].toList.map(_.toString)
      val qs    = questions.toList.map(ev(_))
      validated(names.zip(qs))
    }
}
