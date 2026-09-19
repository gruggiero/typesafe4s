package typesafe4s.typecontract

import scala.NamedTuple.NamedTuple

import typesafe4s.{
  AnswerOf,
  AnswerSet,
  Choice,
  ChoiceAnswer,
  Entry,
  Naming,
  Noul,
  NoulAnswer,
  Question,
  QuestionSet,
  RequestId,
  Score,
  ScoreAnswer,
  SystemOne,
  TypesafeException
}
import typesafe4s.json.Json

// ============================================================================
// TYPED CONTRACT — spec: question-model (change: add-typesafe4s-sdk, spec 3/8)
//
// Gate 1 artifact, approved 2026-09-18. The declarations were promoted to
// typesafe4s-core/src/main/scala/typesafe4s/{AnswerOf,Naming,Question,
// QuestionSet,AnswerSet,SystemOne}.scala at Step 3; this file remains as a
// living compile-time assertion of the approved surface — if the
// implementation drifts from it, this file stops compiling.
//
// Approved surface (Gate 1):
//   type AnswerOf[Q] = Q match { case Question[a] => a }
//   trait Naming { def label(enumCase: String): String }
//   object Naming — given verbatim; val lowerSnake
//   Choice.of[Os <: Tuple](Entry)(using Tuple.Union[Os] <:< String):
//     Choice[Tuple.Union[Os]] — inline, limits 1..255
//   Choice.derived[E](Entry)(using Mirror.SumOf[E], Naming): Choice[E] —
//     inline, singleton cases only, at most 255
//   Score.of[Ls <: Tuple](Entry)(Ls)(using Tuple.Union[Ls] <:< String):
//     Score[Tuple.Size[Ls]] — inline, arity 2..10
//   QuestionSet.validated(IterableOnce[(String, Question[?])]):
//     Either[InvalidQuestion, QuestionSet] — the runtime submission gate
//   QuestionSet.fromNamedTuple[N,V](NamedTuple[N,V])(using Tuple.Union[V]
//     <:< Question[?]): Either[InvalidQuestion, QuestionSet] — inline,
//     non-empty
//   AnswerSet.noul/choice/score(String): Either[MissingAnswer, that kind]
//   SystemOne.renderTyped[A,N,V](A,String)(NamedTuple[N,V])(using
//     StateEncoder[A], Tuple.Union[V] <:< Question[?]):
//     Either[InvalidQuestion, Json]
//   SystemOne.renderDynamic[A](A,String)(IterableOnce[(String,Question[?])])
//     (using StateEncoder[A]): Either[InvalidQuestion, Json]
//   SystemOne.decodeTyped[N,V](NamedTuple[N,V], Option[RequestId], String)
//     (using Tuple.Union[V] <:< Question[?]):
//     Either[TypesafeException, NamedTuple[N, Tuple.Map[V, AnswerOf]]]
//   TypesafeException.InvalidQuestion(Option[String], String) — 14th member
//   ScoreAnswer[+L <: Int] — covariant level index
//
// Approved design decisions (D1–D8):
//   D1  `Choice.of` takes a literal TUPLE type parameter — the union spelling
//       `of["a" | "b"]` is unimplementable on 3.9 (union decomposition needs
//       deprecated -source:3.3; Mirror.Sum rejects top-level unions).
//       `Tuple.Union[Os]` yields the identical literal union.
//   D2  `Score.of` derives the level-count index from the tuple's arity.
//   D3  `TypesafeException.InvalidQuestion` — a NEW (14th) family member:
//       every refusal member describes a SERVICE refusal, so none can
//       honestly report a LOCAL limit violation. `name` is Option: present
//       for a per-question violation, absent for a set-level failure.
//   D4  AnswerSet carries the kind-indexed lookups itself; `choice` returns
//       `ChoiceAnswer[?]` — `ChoiceAnswer[String]` would lie for `derived`.
//   D5  `ScoreAnswer[+L]` — phantom index; covariance enables the dynamic
//       `ScoreAnswer[Int]` lookup without a cast.
//   D6  MissingAnswer covers BOTH an unasked name and a wrong-kind read.
//   D7  One `asInstanceOf` at typed-tuple assembly — pattern type vars
//       cannot correlate Choice[o] → ChoiceAnswer[o] post-decode on 3.9;
//       positional assembly is justified by the decoder's per-name kind
//       guarantee.
//   D8  renderTyped/renderDynamic/decodeTyped are the two call surfaces at
//       core level; ONE decoder (decodeResponse) serves both.
// ============================================================================

private object QuestionModelTypeContract {

  // surface witnesses — each would stop compiling if the promoted surface drifted

  // AnswerOf reduces on each question kind
  val answerOfNoulSig: AnswerOf[Noul] => NoulAnswer                                                                                       = identity
  val answerOfChoiceSig: AnswerOf[Choice["x" | "y"]] => ChoiceAnswer["x" | "y"]                                                           = identity
  val answerOfScoreSig: AnswerOf[Score[3]] => ScoreAnswer[3]                                                                              = identity
  val answerOfTupleSig: Tuple.Map[(Noul, Choice["x" | "y"], Score[3]), AnswerOf] => (NoulAnswer, ChoiceAnswer["x" | "y"], ScoreAnswer[3]) =
    identity

  // the named tuple's field types ARE the AnswerOf reduction
  val namedTupleSig: NamedTuple[("n", "c"), Tuple.Map[(Noul, Choice["x" | "y"]), AnswerOf]] => (NoulAnswer, ChoiceAnswer["x" | "y"]) =
    t => (t.n, t.c)

  // constructors
  val choiceOfSig: Entry => Choice["a" | "b"] = e => Choice.of[("a", "b")](e)
  val scoreOfSig: Entry => Score[3]           = e => Score.of(e)(("a", "b", "c"))

  enum WitnessCase {
    case Alpha, Beta
  }
  val derivedSig: Entry => Choice[WitnessCase] = e => Choice.derived[WitnessCase](e)
  val namingVerbatimSig: Naming                = Naming.verbatim
  val namingLowerSnakeSig: Naming              = Naming.lowerSnake

  // the runtime gate and the named-tuple surface
  val validatedSig: IterableOnce[(String, Question[?])] => Either[TypesafeException.InvalidQuestion, QuestionSet]        = QuestionSet.validated
  val fromNamedTupleSig: NamedTuple[Tuple1["q"], Tuple1[Noul]] => Either[TypesafeException.InvalidQuestion, QuestionSet] =
    qs => QuestionSet.fromNamedTuple(qs)

  // kind-indexed lookups
  val noulLookupSig: AnswerSet => String => Either[TypesafeException.MissingAnswer, NoulAnswer]        = s => s.noul(_)
  val choiceLookupSig: AnswerSet => String => Either[TypesafeException.MissingAnswer, ChoiceAnswer[?]] = s => s.choice(_)
  val scoreLookupSig: AnswerSet => String => Either[TypesafeException.MissingAnswer, ScoreAnswer[Int]] = s => s.score(_)

  // the two call surfaces
  val renderTypedSig: NamedTuple[Tuple1["q"], Tuple1[Noul]] => Either[TypesafeException.InvalidQuestion, Json]                     =
    qs => SystemOne.renderTyped("s", "m")(qs)
  val renderDynamicSig: (String, String) => IterableOnce[(String, Question[?])] => Either[TypesafeException.InvalidQuestion, Json] =
    SystemOne.renderDynamic[String]
  val decodeTypedSig: (NamedTuple[Tuple1["q"], Tuple1[Noul]], Option[RequestId], String) => Either[
    TypesafeException,
    NamedTuple[Tuple1["q"], Tuple1[NoulAnswer]]
  ]                                                                                                                                =
    (qs, id, body) => SystemOne.decodeTyped(qs, id, body)

  // ScoreAnswer[+L]: the declared index widens to Int for the dynamic lookup
  val scoreCovarianceSig: ScoreAnswer[3] => ScoreAnswer[Int] = identity
}

// ============================================================================
// Property obligations (Ring 2) — discharged by typesafe4s.QuestionModelProperties
// ----------------------------------------------------------------------------
// P1 `every-asked-question-is-answered-exactly-once` — genQuestionSet:
//    constructive, 1-20 questions, distinct names by construction, mixed
//    kinds; classify count bucket + kind mix.
//    decode(respondTo(questions)).names == questions.names
// P2 `selected-option-is-always-supplied` — genChoice: constructive, 1-255
//    distinct labels (single-option, 255-boundary, case-differing labels by
//    construction); response selects an index into the set.
//    decode(choice, response).selected ∈ choice.options &&
//      spread.keys == choice.options
// P3 `rubric-position-lies-inside-the-rubric` — genScore: constructive, 2-10
//    levels uniformly (both boundaries always reachable); position drawn as
//    the probability-weighted mean of the generated spread.
//    0 <= answer.position <= levelCount - 1 && spread.keys == levelNumbers
// P4 `both-surfaces-form-the-same-request` — genQuestionSet restricted to a
//    fixed-arity shape expressible as a literal named tuple; renderTyped ==
//    renderDynamic (byte-identical Json).
//
// Compile-negative obligations — outside/QuestionModelCompileNegativeSuite.scala:
//   * `a.noul.confidence` — a Noul answer has no certainty member
//   * `Score.of(i)(Tuple1("one"))` — refuses, naming the two-level minimum
//   * `Score.of(i)` over 11 levels — refuses, naming the ten-level maximum
//   * `Choice.of[(256-literal tuple)]` — refuses, naming the 255-option maximum
//   * `QuestionSet.fromNamedTuple(())` — an evaluation asks at least one
//   * `a.unasked` — a name never asked is not a member of the answer tuple
//   * `a.choiceAsked.noul` — wrong-kind read through the typed surface
//   * NOTE: omission-negatives (dropping an option from a match on a union-
//     typed `choice`) are E029 warnings — invisible to compileErrors; they are
//     discharged by the -Werror build plus living exhaustive-match witnesses
//     (spec-1 scanner-gap, recorded in state.md)
// ============================================================================
