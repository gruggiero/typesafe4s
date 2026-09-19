package typesafe4s.typecontract

import scala.collection.immutable.ListMap

import typesafe4s.{
  Answer,
  AnswerSet,
  Choice,
  ChoiceAnswer,
  Entry,
  Noul,
  NoulAnswer,
  Question,
  QuestionSet,
  RequestId,
  Score,
  ScoreAnswer,
  StateEncoder,
  SystemOne,
  TypesafeException,
  Usage
}
import typesafe4s.json.Json

// ============================================================================
// TYPED CONTRACT — spec: wire-codec (change: add-typesafe4s-sdk, spec 2/8)
//
// Gate 1 artifact, approved 2026-09-18. The declarations were promoted to
// typesafe4s-core/src/main/scala/typesafe4s/{json/Json,Entry,StateEncoder,
// Usage,Question,Answer,QuestionSet,AnswerSet,SystemOne}.scala at Step 3;
// this file remains as a living compile-time assertion of the approved
// surface — if the implementation drifts from it, this file stops compiling.
//
// Approved surface (Gate 1):
//   enum json.Json — JNull | JBool | JNumber(BigDecimal) | JString |
//                    JArray(Vector) | JObject(Vector[(String,Json)])
//   Json.render / Json.parse / Json.ParseError(Int, String)
//   opaque type Entry = Json — text/obj/arr/nothing + fromJson + .json
//   trait StateEncoder[A] + givens: String, Entry, Map, Seq, Option, (A,B),
//     (A,B,C) — NO StateEncoder[Json] (D2: fromJson is the checked path)
//   final case class Usage(Option[Long], Option[Long])
//   sealed trait Answer — NoulAnswer(Double) |
//     ChoiceAnswer[O](O, Map[O,Double], Double) |
//     ScoreAnswer[L<:Int](Double, Map[Int,Entry], Map[Int,Double], Double)
//   sealed trait Question[A] — Noul(Entry, Option[Criteria]) |
//     Choice[O](Entry, ListMap[String,Entry], String=>Option[O]) |
//     Score[L<:Int](Entry, Vector[Entry])
//   final case class QuestionSet private (ListMap) — apply(first, rest*),
//     fromEntries(IterableOnce): Option — non-empty by construction
//   final case class AnswerSet(Map[String,Answer], String, Usage,
//     Option[RequestId])
//   object SystemOne — renderRequest[A](A, String, QuestionSet)(using
//     StateEncoder[A]): Json; decodeResponse(QuestionSet, Option[RequestId],
//     String): Either[ResponseValidation, AnswerSet]
//
// Design decisions approved at Gate 1 (D1–D9, see spec's proof table):
//   Entry restricted recursively; no StateEncoder[Json] given; QuestionSet
//   non-empty by construction; Answer sealed trait added as decoder operand;
//   AnswerSet.requestId populated on both sides; decodeResponse takes the raw
//   body String; Score/ScoreAnswer carry the level-count index; Choice carries
//   decode; Noul.criteria optional as a whole.
// ============================================================================

private object WireCodecTypeContract {

  // surface witnesses — each would stop compiling if the promoted surface drifted
  val renderSig: Json => String                                                                                      = Json.render
  val parseSig: String => Either[Json.ParseError, Json]                                                              = Json.parse
  val parseErrorSig: (Int, String) => Json.ParseError                                                                = Json.ParseError.apply
  val textSig: String => Entry                                                                                       = Entry.text
  val objSig: Seq[(String, Entry)] => Entry                                                                          = Entry.obj
  val arrSig: Seq[Entry] => Entry                                                                                    = Entry.arr
  val nothingSig: Entry                                                                                              = Entry.nothing
  val fromJsonSig: Json => Either[String, Entry]                                                                     = Entry.fromJson
  val entryJsonSig: Entry => Json                                                                                    = Entry.json
  val encoderStringSig: StateEncoder[String]                                                                         = summon[StateEncoder[String]]
  val encoderEntrySig: StateEncoder[Entry]                                                                           = summon[StateEncoder[Entry]]
  val encoderMapSig: StateEncoder[Map[String, String]]                                                               = summon[StateEncoder[Map[String, String]]]
  val encoderSeqSig: StateEncoder[Seq[String]]                                                                       = summon[StateEncoder[Seq[String]]]
  val encoderOptionSig: StateEncoder[Option[String]]                                                                 = summon[StateEncoder[Option[String]]]
  val encoderTuple2Sig: StateEncoder[(String, Entry)]                                                                = summon[StateEncoder[(String, Entry)]]
  val encoderTuple3Sig: StateEncoder[(String, Entry, String)]                                                        = summon[StateEncoder[(String, Entry, String)]]
  val usageSig: (Option[Long], Option[Long]) => Usage                                                                = Usage.apply
  val noulSig: (Entry, Option[Noul.Criteria]) => Noul                                                                = Noul.apply
  val criteriaSig: (Entry, Entry) => Noul.Criteria                                                                   = Noul.Criteria.apply
  val choiceSig: (Entry, ListMap[String, Entry], String => Option[String]) => Choice[String]                         = Choice.apply[String]
  val scoreSig: (Entry, Vector[Entry]) => Score[?]                                                                   = Score.apply[Int]
  val noulAnswerSig: Double => NoulAnswer                                                                            = NoulAnswer.apply
  val choiceAnswerSig: (String, Map[String, Double], Double) => ChoiceAnswer[String]                                 = ChoiceAnswer.apply[String]
  val scoreAnswerSig: (Double, Map[Int, Entry], Map[Int, Double], Double) => ScoreAnswer[Int]                        = ScoreAnswer.apply[Int]
  val questionSetSig: ((String, Question[?]), Seq[(String, Question[?])]) => QuestionSet                             = QuestionSet.apply
  val fromEntriesSig: IterableOnce[(String, Question[?])] => Option[QuestionSet]                                     = QuestionSet.fromEntries
  val questionNamesSig: QuestionSet => Set[String]                                                                   = _.names
  val questionSizeSig: QuestionSet => Int                                                                            = _.size
  val answerSetSig: (Map[String, Answer], String, Usage, Option[RequestId]) => AnswerSet                             = AnswerSet.apply
  val answerNamesSig: AnswerSet => Set[String]                                                                       = _.names
  val renderRequestSig: (String, String, QuestionSet) => Json                                                        = SystemOne.renderRequest[String]
  val decodeSig: (QuestionSet, Option[RequestId], String) => Either[TypesafeException.ResponseValidation, AnswerSet] =
    SystemOne.decodeResponse

  // spec: wire-codec — the Json sum is closed over the six wire shapes;
  // omitting one is reported before the program runs
  def jsonKind(j: Json): String = j match {
    case Json.JNull      => "null"
    case _: Json.JBool   => "bool"
    case _: Json.JNumber => "number"
    case _: Json.JString => "string"
    case _: Json.JArray  => "array"
    case _: Json.JObject => "object"
  }

  // spec: wire-codec — the decoded answer is always one of the three kinds
  // the wire marker can name; exhaustive, no catch-all
  def answerKind(a: Answer): String = a match {
    case _: NoulAnswer      => "noul"
    case _: ChoiceAnswer[?] => "choice"
    case _: ScoreAnswer[?]  => "score"
  }

  // spec: wire-codec — the question family is closed over the three asked
  // kinds; exhaustive, no catch-all
  def questionKind(q: Question[?]): String = q match {
    case _: Noul      => "noul"
    case _: Choice[?] => "choice"
    case _: Score[?]  => "score"
  }
}

// ============================================================================
// Property obligations (Ring 2) — discharged by typesafe4s.WireCodecProperties
// ----------------------------------------------------------------------------
// P1 `render-then-parse-is-identity`
//    genJson — constructive, depth <= 4; alphabet includes '"', '\\', '\n',
//    non-ASCII; objects 0-6 distinct members (empty object reachable).
//    forAll { (value: Json) => Json.parse(Json.render(value)) == Right(value) }
// P2 `every-asked-question-appears-once`
//    genQuestionSet — constructive; names q1..qn distinct by construction;
//    1-20 questions, mixed kinds.
//    rendered question-member keys == questions.names
// P3 `reading-is-total-over-well-formed-responses`
//    genResponseCase — response built FROM the question set so markers agree
//    by construction; ~half of runs perturb one guaranteed-non-fitting path.
//    Right(answers) => answers.names == questions.names
//    Left(failure)  => failure.path.nonEmpty
// P4 recorded fixtures — the documented request and response examples as
//    byte-compared fixtures (typesafe4s-core/src/test/resources/)
//
// Compile-negative obligations — outside/WireCodecCompileNegativeSuite.scala:
//   * `Entry(Json.JNumber(1.0))` — no public apply (opaque); smart constructors
//     accept only entry shapes, and obj/arr children are Entry — a number or
//     boolean at top level does not compile
//   * `Entry.obj("count" -> Json.JNumber(1.0))` — child is Json, not Entry
//   * `QuestionSet()` / `renderRequest(s, m, QuestionSet())` — an empty
//     question set is unrepresentable (apply requires a first entry)
//
// Build obligations (tier 4 — not a test):
//   * core dependency assertion: `checkCoreDependencies` in build.sbt asserts
//     `core / Compile / externalDependencyClasspath` holds only org.scala-lang
//     jars, wired into `sbt check`
//   * adapter dependency assertions — CARRY-FORWARD: the four adapter modules
//     are task §11 (not part of this spec's task set); the per-adapter
//     assertion pattern lands with them
// ============================================================================
