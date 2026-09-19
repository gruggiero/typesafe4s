package typesafe4s

import scala.collection.immutable.ListMap
import scala.compiletime.{constValue, constValueTuple, error, summonAll}
import scala.deriving.Mirror

// ============================================================================
// spec: wire-codec — operand data shapes (order §mutual-deps #1)
// The question data shapes land here so the codec has its operands. Owned and
// verified by question-model (spec 3) — this spec introduces the PLAIN shapes
// only: no `AnswerOf` match type, no named-tuple surface, no inline limits,
// no `Mirror.derived`.
// ============================================================================
sealed trait Question[A]

// criteria is optional as a whole; when present both meanings are supplied —
// rendered under the documented names "true"/"false"
final case class Noul(instructions: Entry, criteria: Option[Noul.Criteria] = None) extends Question[NoulAnswer]

object Noul {
  final case class Criteria(yes: Entry, no: Entry)
}

// options preserve declaration order (ListMap) so rendered criteria are
// reproducible; a no-description option is Entry.nothing — rendered null.
// `decode` maps a wire option key to the caller's option type.
final case class Choice[O](instructions: Entry, options: ListMap[String, Entry], decode: String => Option[O]) extends Question[ChoiceAnswer[O]]

object Choice {

  // spec: question-model — a Choice whose options are written in source as a
  // literal tuple type; the answer's `choice` refines to the literal union so
  // the caller can match on it exhaustively (Scenario: A selected option can
  // be exhausted over). Compile-time: at least one option, at most 255 —
  // `inline if` + `error` refuses before the program runs.
  // stryker4s: mutations inside the constValue checks can only produce a
  // compile error at expansion, never a runtime-killable mutant — the limits
  // are discharged by Ring 0 + compile-negatives, so they are suppressed here
  @SuppressWarnings(
    Array(
      "stryker4s.mutation.ConditionalExpression",
      "stryker4s.mutation.EqualityOperator",
      "stryker4s.mutation.BooleanLiteral",
      "stryker4s.mutation.StringLiteral"
    )
  )
  inline def of[Os <: Tuple](instructions: Entry)(using ev: Tuple.Union[Os] <:< String): Choice[Tuple.Union[Os]] =
    inline if constValue[Tuple.Size[Os]] > 255 then error("a Choice accepts at most 255 options")
    else inline if constValue[Tuple.Size[Os]] < 1 then error("a Choice needs at least one option")
    else {
      val labels = constValueTuple[Os].toList.map(ev(_))
      Choice[Tuple.Union[Os]](
        instructions,
        ListMap.from(labels.map(_ -> Entry.nothing)),
        // `ev` proves every union member is a String, so a wire key that IS
        // one of the generated labels is one of the literal values — the
        // cast re-narrows what `ev` already guarantees
        k => Option.when(labels.contains(k))(k.asInstanceOf[Tuple.Union[Os]])
      )
    }

  // spec: question-model — Requirement: Choice options may be taken from a
  // domain enumeration. `Mirror.SumOf` supplies the case labels (sent as the
  // option keys, transformed by `naming`) and the case values (the selected
  // option comes back as an enumeration member). Only singleton cases are
  // admissible — summonAll[ValueOf] refuses a parameterized case before the
  // program runs. Compile-time: at most 255 cases.
  @SuppressWarnings(
    Array(
      "stryker4s.mutation.ConditionalExpression",
      "stryker4s.mutation.EqualityOperator",
      "stryker4s.mutation.BooleanLiteral",
      "stryker4s.mutation.StringLiteral"
    )
  )
  inline def derived[E](instructions: Entry)(using m: Mirror.SumOf[E], naming: Naming): Choice[E] =
    inline if constValue[Tuple.Size[m.MirroredElemLabels]] > 255 then error("a Choice accepts at most 255 options")
    else {
      val labels  = constValueTuple[m.MirroredElemLabels].toList.map(_.toString)
      // every element is a ValueOf[case-singleton]; the case singletons are
      // subtypes of E, so .value is an E
      val cases   = summonAll[Tuple.Map[m.MirroredElemTypes, ValueOf]].toList.map(_.asInstanceOf[ValueOf[? <: E]].value)
      // declaration order preserved: labels and cases come from the same
      // mirror position lists
      val options = labels.map(naming.label).zip(cases)
      Choice[E](
        instructions,
        ListMap.from(options.map { case (k, _) => k -> Entry.nothing }),
        k => options.toMap.get(k)
      )
    }
}

// levels are ordered low to high; L is the declared count — the index spec 3
// hangs its compile-time checks on
final case class Score[L <: Int](instructions: Entry, levels: Vector[Entry]) extends Question[ScoreAnswer[L]]

object Score {

  // spec: question-model — a Score whose rubric levels are written in source;
  // the level-count index is the tuple's arity, refused before the program
  // runs outside 2..10 (Scenarios: too few / too many levels). String levels
  // only (the documented shape); `Entry` levels use the runtime `Score[L]`
  // apply and are caught by submit-time validation.
  @SuppressWarnings(
    Array(
      "stryker4s.mutation.ConditionalExpression",
      "stryker4s.mutation.EqualityOperator",
      "stryker4s.mutation.BooleanLiteral",
      "stryker4s.mutation.StringLiteral"
    )
  )
  inline def of[Ls <: Tuple](instructions: Entry)(levels: Ls)(using ev: Tuple.Union[Ls] <:< String): Score[Tuple.Size[Ls]] =
    inline if constValue[Tuple.Size[Ls]] < 2 then error("a Score needs at least two levels")
    else inline if constValue[Tuple.Size[Ls]] > 10 then error("a Score accepts at most ten levels")
    else
      Score[Tuple.Size[Ls]](instructions, (levels: Ls).toList.map(l => Entry.text(ev(l))).toVector)
}
