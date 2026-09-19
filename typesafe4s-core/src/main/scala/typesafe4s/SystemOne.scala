package typesafe4s

import scala.NamedTuple.NamedTuple

import typesafe4s.json.Json

// ============================================================================
// spec: wire-codec — the codec surface
// renderRequest produces the POST /v1/systemone body as a Json AST (the
// transport prints it). decodeResponse reads the raw body: a body that is not
// JSON is a body that does not fit — one failure channel, ResponseValidation,
// naming the path at which it stopped fitting.
// ============================================================================
object SystemOne {

  // spec: question-model — the typed call surface at core level: a question
  // set written in source as a named tuple is validated and rendered; the
  // request keys are the field names (Scenario: Request keys come from the
  // names already written)
  inline def renderTyped[A, N <: Tuple, V <: Tuple](state: A, model: String)(
    questions: NamedTuple[N, V]
  )(using enc: StateEncoder[A], ev: Tuple.Union[V] <:< Question[?]): Either[TypesafeException.InvalidQuestion, Json] =
    QuestionSet.fromNamedTuple(questions).map(renderRequest(state, model, _))

  // spec: question-model — the dynamic call surface at core level: a
  // question set assembled at run time is validated and rendered. Byte-
  // identical to `renderTyped` on the same set (Property: Both call surfaces
  // form the same request) — both route through `validated` + `renderRequest`
  def renderDynamic[A](state: A, model: String)(
    questions: IterableOnce[(String, Question[?])]
  )(using enc: StateEncoder[A]): Either[TypesafeException.InvalidQuestion, Json] =
    QuestionSet.validated(questions).map(renderRequest(state, model, _))

  // spec: question-model — the typed answer surface: the response is decoded
  // through the ONE decoder (decodeResponse) and the answers are assembled
  // positionally into a named tuple whose field types are `AnswerOf` of the
  // question types — `a.refundRequested` is a `NoulAnswer`, `a.department` is
  // a `ChoiceAnswer[that choice's option union]`
  inline def decodeTyped[N <: Tuple, V <: Tuple](
    questions: NamedTuple[N, V],
    requestId: Option[RequestId],
    body: String
  )(using ev: Tuple.Union[V] <:< Question[?]): Either[TypesafeException, NamedTuple[N, Tuple.Map[V, AnswerOf]]] =
    QuestionSet.fromNamedTuple(questions).flatMap { qs =>
      decodeResponse(qs, requestId, body).flatMap(assembleTyped(qs, _))
    }

  // spec: effect-portability — the positional assembly, extracted so the
  // client's typed surface and decodeTyped share ONE assembly: decodeResponse
  // guarantees one answer per asked name, shaped by that name's question, so
  // position i of the entry list carries AnswerOf[V_i] — the single
  // justified cast (D7); a missing answer would be a decoder defect,
  // reported honestly rather than raised
  private[typesafe4s] def assembleTyped[N <: Tuple, V <: Tuple](
    questions: QuestionSet,
    set: AnswerSet
  ): Either[TypesafeException, NamedTuple[N, Tuple.Map[V, AnswerOf]]] =
    questions.entries.keys.toList
      .foldRight[Either[TypesafeException, List[Answer]]](Right(Nil)) { (k, acc) =>
        for {
          as <- acc
          a  <- set.answers
                  .get(k)
                  .toRight(
                    TypesafeException.ResponseValidation(set.requestId, s"answers.$k", "no answer was returned for the asked question")
                  )
        } yield a :: as
      }
      .map(as =>
        as.foldRight(EmptyTuple: Tuple)(_ *: _).asInstanceOf[NamedTuple[N, Tuple.Map[V, AnswerOf]]]
      ) // danger-scan:allow justified cast D7 — positional assembly is provably correct (block comment above)

  def renderRequest[A](state: A, model: String, questions: QuestionSet)(using enc: StateEncoder[A]): Json =
    Json.JObject(
      Vector(
        "state"     -> enc.encode(state).json,
        "model"     -> Json.JString(model),
        "questions" -> Json.JObject(questions.entries.toVector.map { case (name, q) => name -> renderQuestion(q) })
      )
    )

  // each kind carries its own marker and its own criteria shape: a noul's
  // optional pair of meanings, a choice's map of options, a score's ordered
  // list of levels
  private def renderQuestion(question: Question[?]): Json = question match {
    case noul: Noul        =>
      Json.JObject(
        Vector(
          "type"         -> Json.JString("noul"),
          "instructions" -> noul.instructions.json
        ) ++ noul.criteria.toVector.map { c =>
          "criteria" -> Json.JObject(Vector("true" -> c.yes.json, "false" -> c.no.json))
        }
      )
    case choice: Choice[?] =>
      Json.JObject(
        Vector(
          "type"         -> Json.JString("choice"),
          "instructions" -> choice.instructions.json,
          "criteria"     -> Json.JObject(choice.options.toVector.map { case (name, e) => name -> e.json })
        )
      )
    case score: Score[?]   =>
      Json.JObject(
        Vector(
          "type"         -> Json.JString("score"),
          "instructions" -> score.instructions.json,
          "criteria"     -> Json.JArray(score.levels.map(_.json))
        )
      )
  }

  def decodeResponse(
    questions: QuestionSet,
    requestId: Option[RequestId],
    body: String
  ): Either[TypesafeException.ResponseValidation, AnswerSet] = {
    val reader = new ResponseReader(requestId)
    Json.parse(body) match {
      case Left(parseError)             =>
        reader.invalid("$", s"the body is not JSON (${parseError.detail} at position ${parseError.position})")
      case Right(Json.JObject(members)) => reader.read(questions, members)
      case Right(_)                     =>
        reader.invalid("$", "the body is a JSON value, not an object")
    }
  }

  // the reader holds the caller-supplied trace so every fit failure reports it
  final private class ResponseReader(requestId: Option[RequestId]) {

    private type Result[A] = Either[TypesafeException.ResponseValidation, A]

    def invalid[A](path: String, detail: String): Result[A] =
      Left(TypesafeException.ResponseValidation(requestId, path, detail))

    // spec: wire-codec — the response exposes the answering model, one answer
    // per asked question, and token counts independently absent
    def read(questions: QuestionSet, members: Vector[(String, Json)]): Result[AnswerSet] =
      for {
        modelJson   <- required(members, "model", "model")
        model       <- asString("model", modelJson)
        answersJson <- required(members, "answers", "answers")
        answerMap   <- asObject("answers", answersJson)
        usage       <- readUsage(members)
        answers     <- readAnswers(questions, answerMap)
      } yield AnswerSet(answers, model, usage, requestId)

    private def member(members: Vector[(String, Json)], name: String): Option[Json] =
      members.collectFirst { case (`name`, value) => value }

    private def required(members: Vector[(String, Json)], path: String, name: String): Result[Json] =
      member(members, name).toRight(TypesafeException.ResponseValidation(requestId, path, s"no '$name' member"))

    private def asObject(path: String, value: Json): Result[Vector[(String, Json)]] = value match {
      case Json.JObject(members) => Right(members)
      case _                     => invalid(path, "expected an object") // danger-scan:allow unrecognized JSON maps to a decode error, never a value
    }

    private def asString(path: String, value: Json): Result[String] = value match {
      case Json.JString(s) => Right(s)
      case _               => invalid(path, "expected a string") // danger-scan:allow unrecognized JSON maps to a decode error, never a value
    }

    private def asDouble(path: String, value: Json): Result[Double] = value match {
      case Json.JNumber(n) => Right(n.toDouble)
      case _               => invalid(path, "expected a number") // danger-scan:allow unrecognized JSON maps to a decode error, never a value
    }

    private def asLong(path: String, value: Json): Result[Long] = value match {
      case Json.JNumber(n) if n.isWhole && n.isValidLong => Right(n.toLong)
      case _                                             => invalid(path, "expected a whole number") // danger-scan:allow unrecognized JSON maps to a decode error, never a value
    }

    // spec: wire-codec — token counts may be missing, each independently
    private def readUsage(members: Vector[(String, Json)]): Result[Usage] =
      member(members, "usage") match {
        case None        => Right(Usage(None, None))
        case Some(value) =>
          asObject("usage", value).flatMap { ms =>
            for {
              input  <- readCount(ms, "input_tokens")
              output <- readCount(ms, "output_tokens")
            } yield Usage(input, output)
          }
      }

    private def readCount(members: Vector[(String, Json)], name: String): Result[Option[Long]] =
      member(members, name) match {
        case None        => Right(None)
        case Some(value) => asLong(s"usage.$name", value).map(Some(_))
      }

    // one answer per asked question — members for names nobody asked are
    // passed over like every other unrecognised member
    private def readAnswers(questions: QuestionSet, members: Vector[(String, Json)]): Result[Map[String, Answer]] =
      questions.entries.foldLeft[Result[Map[String, Answer]]](Right(Map.empty)) { case (acc, (name, question)) =>
        val path = s"answers.$name"
        for {
          answers <- acc
          value   <- member(members, name).toRight(
                       TypesafeException.ResponseValidation(requestId, path, "no answer was returned for the asked question")
                     )
          answer  <- readAnswer(path, question, value)
        } yield answers + (name -> answer)
      }

    // spec: wire-codec — decoding dispatches on the answer's own type marker;
    // an unrecognised marker, or a marker that does not fit the asked kind,
    // is rejected at the marker
    private def readAnswer(path: String, question: Question[?], value: Json): Result[Answer] =
      asObject(path, value).flatMap { ms =>
        required(ms, path, "type").flatMap(asString(s"$path.type", _)).flatMap {
          case "noul"   =>
            question match {
              case _: Noul => readNoul(ms, path) // danger-scan:allow typed dispatch, not a value-mapping catch-all
              case _       =>
                invalid(
                  s"$path.type",
                  "the answer is a noul but the asked question is not"
                ) // danger-scan:allow unrecognized variant maps to a decode error, never a value
            }
          case "choice" =>
            question match {
              case c: Choice[?] => readChoice(ms, path, c)
              case _            =>
                invalid(
                  s"$path.type",
                  "the answer is a choice but the asked question is not"
                ) // danger-scan:allow unrecognized variant maps to a decode error, never a value
            }
          case "score"  =>
            question match {
              case s: Score[?] => readScore(ms, path, s.levels.size)
              case _           =>
                invalid(
                  s"$path.type",
                  "the answer is a score but the asked question is not"
                ) // danger-scan:allow unrecognized variant maps to a decode error, never a value
            }
          case other    =>
            invalid(
              s"$path.type",
              s"'$other' is not a recognised answer type"
            ) // danger-scan:allow unrecognized variant maps to a decode error, never a value
        }
      }

    private def readNoul(ms: Vector[(String, Json)], path: String): Result[Answer] =
      for {
        noulJson <- required(ms, path, "noul")
        noul     <- asDouble(s"$path.noul", noulJson)
      } yield NoulAnswer(noul)

    private def readChoice[O](ms: Vector[(String, Json)], path: String, question: Choice[O]): Result[Answer] =
      for {
        choiceJson <- required(ms, path, "choice")
        choiceKey  <- asString(s"$path.choice", choiceJson)
        choice     <- question
                        .decode(choiceKey)
                        .fold(
                          invalid[O](s"$path.choice", s"the selected option '$choiceKey' is not one supplied")
                        )(Right(_))
        probsJson  <- required(ms, path, "probabilities")
        probsMs    <- asObject(s"$path.probabilities", probsJson)
        probs      <- readChoiceProbabilities(probsMs, s"$path.probabilities", question.options.keySet, question.decode)
        confJson   <- required(ms, path, "confidence")
        confidence <- asDouble(s"$path.confidence", confJson)
      } yield ChoiceAnswer(choice, probs, confidence)

    // the spread is defined over exactly the supplied option set — a wire key
    // outside it fails per key, and keys covering only a subset fail the whole
    // member (Ring 8: an incomplete spread is a violation, not an answer)
    private def readChoiceProbabilities[O](
      members: Vector[(String, Json)],
      path: String,
      supplied: scala.collection.immutable.Set[String],
      decode: String => Option[O]
    ): Result[Map[O, Double]] =
      members
        .foldLeft[Result[Map[O, Double]]](Right(Map.empty)) { case (acc, (key, value)) =>
          for {
            probs  <- acc
            option <- decode(key).fold(invalid[O](s"$path.$key", s"'$key' is not one of the supplied options"))(Right(_))
            prob   <- asDouble(s"$path.$key", value)
          } yield probs + (option -> prob)
        }
        .flatMap { probs =>
          val missing = supplied -- members.map(_._1).toSet
          if (missing.isEmpty) Right(probs)
          else invalid(path, s"the spread does not cover every supplied option (missing: ${missing.mkString(", ")})")
        }

    private def readScore(ms: Vector[(String, Json)], path: String, levelCount: Int): Result[Answer] =
      for {
        scoreJson  <- required(ms, path, "score")
        score      <- asDouble(s"$path.score", scoreJson)
        legendJson <- required(ms, path, "legend")
        legendMs   <- asObject(s"$path.legend", legendJson)
        legend     <- readLegend(legendMs, s"$path.legend", levelCount)
        probsJson  <- required(ms, path, "probabilities")
        probsMs    <- asObject(s"$path.probabilities", probsJson)
        probs      <- readLevelProbabilities(probsMs, s"$path.probabilities", levelCount)
        confJson   <- required(ms, path, "confidence")
        confidence <- asDouble(s"$path.confidence", confJson)
      } yield ScoreAnswer(score, legend, probs, confidence)

    // a level key names a declared level, in canonical zero-based form — a key
    // like "-1", "+1", "01" or one past the declared range does not fit
    private def readLevelKey(key: String, path: String, levelCount: Int): Result[Int] =
      key.toIntOption match {
        case Some(level) if level >= 0 && level < levelCount && level.toString == key => Right(level)
        case _                                                                        =>
          invalid(
            path,
            s"'$key' is not a level number in the declared range"
          ) // danger-scan:allow unrecognized key maps to a decode error, never a value
      }

    // legend values are entry-shaped; a non-entry value names the deeper path.
    // The legend is defined over exactly the declared levels — a subset fails
    // the whole member (Ring 8: an incomplete legend is a violation)
    private def readLegend(members: Vector[(String, Json)], path: String, levelCount: Int): Result[Map[Int, Entry]] =
      members
        .foldLeft[Result[Map[Int, Entry]]](Right(Map.empty)) { case (acc, (key, value)) =>
          for {
            legend <- acc
            level  <- readLevelKey(key, s"$path.$key", levelCount)
            entry  <- Entry.fromJson(value) match {
                        case Right(entry) => Right(entry)
                        case Left(sub)    =>
                          invalid(s"$path.$key${sub.stripPrefix("$")}", "a legend value must be text, object, array, or nothing")
                      }
          } yield legend + (level -> entry)
        }
        .flatMap(completeCoverage(_, path, levelCount, "legend"))

    // the spread is defined over exactly the declared level numbers — a subset
    // fails the whole member (Ring 8: an incomplete spread is a violation)
    private def readLevelProbabilities(members: Vector[(String, Json)], path: String, levelCount: Int): Result[Map[Int, Double]] =
      members
        .foldLeft[Result[Map[Int, Double]]](Right(Map.empty)) { case (acc, (key, value)) =>
          for {
            probs <- acc
            level <- readLevelKey(key, s"$path.$key", levelCount)
            prob  <- asDouble(s"$path.$key", value)
          } yield probs + (level -> prob)
        }
        .flatMap(completeCoverage(_, path, levelCount, "spread"))

    // keys that decode cleanly but cover only a subset of the declared levels
    // still violate the over-exactly-those-levels invariant
    private def completeCoverage[A](decoded: Map[Int, A], path: String, levelCount: Int, what: String): Result[Map[Int, A]] = {
      val missing = (0 until levelCount).toSet -- decoded.keySet
      if (missing.isEmpty) Right(decoded)
      else invalid(path, s"the $what does not cover every declared level (missing: ${missing.mkString(", ")})")
    }
  }
}
