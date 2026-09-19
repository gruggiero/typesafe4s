package typesafe4s

import scala.collection.immutable.ListMap

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.{classify, forAll}

import typesafe4s.json.Json

// ============================================================================
// TEST ORACLE — spec: question-model (change: add-typesafe4s-sdk, spec 3/8)
//
// Written from the spec and the Gate-1 contract BEFORE any implementation.
// Every test cites its spec source. Generators are constructive — ScalaCheck's
// classify is informational only, so interesting cases are reached by
// construction, never by rare draws.
//
// The question/response generators share their shape with WireCodecProperties
// (the wire contract is the same); they are re-stated here so this suite is
// self-contained against the spec it discharges.
// ============================================================================
class QuestionModelProperties extends ScalaCheckSuite {

  // --------------------------------------------------------------------------
  // Generators — shared shape with wire-codec (spec 2)
  // --------------------------------------------------------------------------

  private val genTextChar: Gen[Char] = Gen.frequency(
    6 -> Gen.alphaNumChar,
    4 -> Gen.oneOf('"', '\\', '\n', '\r', '\t', '\b', '\f'),
    2 -> Gen.choose(0x00.toChar, 0x1f.toChar),
    3 -> Gen.choose(0x80.toChar, 0x2fff.toChar)
  )
  private val genText: Gen[String]   = Gen.listOf(genTextChar).map(_.mkString)

  private def distinctKeys[A](ms: List[(String, A)]): List[(String, A)] =
    ms.foldLeft(List.empty[(String, A)]) { (acc, m) =>
      if (acc.exists(_._1 == m._1)) acc else acc :+ m
    }

  private def genEntryJson(depth: Int): Gen[Json] =
    if (depth <= 0) Gen.frequency(1 -> Gen.const(Json.JNull), 4 -> genText.map(Json.JString(_)))
    else
      Gen.frequency(
        4                           -> Gen.frequency(1 -> Gen.const(Json.JNull), 4 -> genText.map(Json.JString(_))),
        1                           -> Gen.choose(0, 3).flatMap(n => Gen.listOfN(n, genEntryJson(depth - 1))).map(vs => Json.JArray(vs.toVector)),
        1                           -> Gen
          .choose(0, 3)
          .flatMap(n => Gen.listOfN(n, Gen.zip(genText, genEntryJson(depth - 1))))
          .map(ms => Json.JObject(distinctKeys(ms).toVector))
      )

  private val genEntry: Gen[Entry] =
    genEntryJson(2).map(j => Entry.fromJson(j).fold(p => throw new IllegalStateException(s"genEntryJson produced a non-entry shape at $p"), identity))

  private val genNoul: Gen[Noul] =
    for {
      instr <- genEntry
      crit  <- Gen.option(Gen.zip(genEntry, genEntry).map { case (y, n) => Noul.Criteria(y, n) })
    } yield Noul(instr, crit)

  // spec: question-model — Property: A selected option is always one the
  // caller supplied. genChoice — constructive, 1-255 distinct labels; the
  // single-option and 255-boundary sizes and case-differing labels are all
  // reached by construction.
  private val genChoice: Gen[Choice[String]] =
    for {
      instr <- genEntry
      nOpts <- Gen.frequency(1 -> Gen.const(1), 1 -> Gen.const(255), 8 -> Gen.choose(2, 254))
      mixed <- Gen.oneOf(true, false)
      opts  <- Gen.listOfN(nOpts, genEntry)
    } yield {
      // labels are distinct by construction; when `mixed`, the first pair
      // differs only by case ("alpha" / "ALPHA" are distinct wire keys)
      val labels = (1 to nOpts).toList.map { i =>
        if (mixed && i == 1) "alpha"
        else if (mixed && i == 2) "ALPHA"
        else s"opt$i"
      }
      Choice[String](instr, ListMap.from(labels.zip(opts)), Some(_))
    }

  // spec: question-model — Property: A rubric position lies inside the rubric.
  // genScore — constructive, the whole documented 2-10 range uniformly, so
  // both boundaries are always reachable.
  private val genScore: Gen[Score[Int]] =
    for {
      instr   <- genEntry
      nLevels <- Gen.choose(2, 10)
      levels  <- Gen.listOfN(nLevels, genEntry)
    } yield Score[Int](instructions = instr, levels = levels.toVector)

  private val genQuestion: Gen[Question[?]] = Gen.frequency(
    3 -> genNoul.map(q => q: Question[?]),
    3 -> genChoice.map(q => q: Question[?]),
    3 -> genScore.map(q => q: Question[?])
  )

  // spec: question-model — Property: Every asked question is answered exactly
  // once. genQuestionSet — constructive, 1-20 questions, names drawn as q1..qn
  // so distinctness cannot fail.
  private val genQuestionSet: Gen[QuestionSet] =
    Gen.choose(1, 20).flatMap { n =>
      Gen.listOfN(n, genQuestion).map { qs =>
        val entries = qs.zipWithIndex.map { case (q, i) => s"q${i + 1}" -> (q: Question[?]) }
        QuestionSet(entries.head, entries.tail*)
      }
    }

  private def genAnswerFor(name: String, q: Question[?]): Gen[(String, Json)] =
    q match {
      case _: Noul      =>
        Gen.choose(0.0, 1.0).map(d => name -> Json.JObject(Vector("type" -> Json.JString("noul"), "noul" -> Json.JNumber(BigDecimal(d)))))
      case c: Choice[?] =>
        for {
          key  <- Gen.oneOf(c.options.keys.toVector)
          conf <- Gen.choose(0.0, 1.0)
        } yield {
          val probs: Vector[(String, Json)] =
            c.options.keys.toVector.map(k => k -> Json.JNumber(BigDecimal(1.0 / c.options.size)))
          name -> Json.JObject(
            Vector(
              "type"          -> Json.JString("choice"),
              "choice"        -> Json.JString(key),
              "probabilities" -> Json.JObject(probs),
              "confidence"    -> Json.JNumber(BigDecimal(conf))
            )
          )
        }
      case s: Score[?]  =>
        // the position is the probability-weighted mean of a spread over the
        // declared levels — inside the rubric by construction
        for {
          probs <- Gen.sequence[List[Double], Double](s.levels.indices.map(_ => Gen.choose(0.0, 1.0)).toList)
          conf  <- Gen.choose(0.0, 1.0)
        } yield {
          val total                          = probs.sum
          val weights                        = if (total == 0.0) probs.map(_ => 1.0 / s.levels.size) else probs.map(_ / total)
          val position                       = weights.zipWithIndex.map { case (w, i) => w * i }.sum
          val legend: Vector[(String, Json)] = s.levels.indices.map(i => i.toString -> s.levels(i).json).toVector
          val probsJson                      = s.levels.indices.map(i => i.toString -> Json.JNumber(BigDecimal(weights(i)))).toVector
          name -> Json.JObject(
            Vector(
              "type"          -> Json.JString("score"),
              "score"         -> Json.JNumber(BigDecimal(position)),
              "legend"        -> Json.JObject(legend),
              "probabilities" -> Json.JObject(probsJson),
              "confidence"    -> Json.JNumber(BigDecimal(conf))
            )
          )
        }
    }

  private def genWellFormedResponse(questions: QuestionSet): Gen[Json] =
    for {
      answers <- Gen.sequence[List[(String, Json)], (String, Json)](
                   questions.entries.toList.map { case (name, q) => genAnswerFor(name, q) }
                 )
      model   <- Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)
    } yield Json.JObject(
      Vector(
        "model"   -> Json.JString(model),
        "answers" -> Json.JObject(answers.toVector)
      )
    )

  // --------------------------------------------------------------------------
  // Requirement: An answer's shape follows from the question asked
  // --------------------------------------------------------------------------

  // spec: question-model — Proof Obligation: a probability lies in [0, 1]
  // (tier 3 — the bound is a property of data the server sends) and each
  // answer kind exposes exactly its own members (tier 1 is the type contract;
  // here the decoder surfaces each kind's answer under the asked name)
  property("answer-shape-follows-question") {
    forAll(
      for {
        questions <- genQuestionSet
        response  <- genWellFormedResponse(questions)
      } yield (questions, response)
    ) { case (questions, response) =>
      val kindMix = questions.entries.values.map {
        case _: Noul      => "noul"
        case _: Choice[?] => "choice"
        case _: Score[?]  => "score"
      }.toSet
      classify(true, if (kindMix.size == 1) kindMix.head else "mixed") {
        SystemOne.decodeResponse(questions, None, Json.render(response)) match {
          case Right(set) =>
            questions.entries.forall { case (name, q) =>
              set.answers.get(name).exists {
                case NoulAnswer(n)      => q.isInstanceOf[Noul] && n >= 0.0 && n <= 1.0
                case a: ChoiceAnswer[?] => q.isInstanceOf[Choice[?]] && a.probabilities.keySet.contains(a.choice)
                case a: ScoreAnswer[?]  => q.isInstanceOf[Score[?]] && a.score >= 0.0
              }
            }
          case Left(_)    => false
        }
      }
    }
  }

  // spec: question-model — Scenario: A Noul answer offers a probability and
  // nothing else. The "nothing else" half is tier 1 (no certainty member —
  // the compile-negative suite holds that); here the probability is read.
  test("a noul answer offers a probability and nothing else") {
    val qs   = QuestionSet("refund" -> Noul(Entry.text("Refund?")))
    val body = """{"model":"m","answers":{"refund":{"type":"noul","noul":0.9}}}"""
    SystemOne.decodeResponse(qs, None, body) match {
      case Right(set) =>
        assertEquals(set.answers("refund"), NoulAnswer(0.9))
        assert(set.noul("refund").contains(NoulAnswer(0.9)), "the kind-indexed lookup returns the same probability")
      case Left(f)    => fail(s"a noul answer was rejected: ${f.path} ${f.detail}")
    }
  }

  // spec: question-model — Scenario: A Score answer may sit between levels
  test("a score answer may sit between levels") {
    val qs   = QuestionSet("sev" -> Score[3](Entry.text("s?"), Vector(Entry.text("low"), Entry.text("mid"), Entry.text("high"))))
    val body =
      """{"model":"m","answers":{"sev":{"type":"score","score":1.35,"legend":{"0":"low","1":"mid","2":"high"},""" +
        """"probabilities":{"0":0.1,"1":0.55,"2":0.35},"confidence":0.7}}}"""
    SystemOne.decodeResponse(qs, None, body) match {
      case Right(set) =>
        val answer = ScoreAnswer[Int](
          1.35,
          Map(0 -> Entry.text("low"), 1 -> Entry.text("mid"), 2 -> Entry.text("high")),
          Map(0 -> 0.1, 1               -> 0.55, 2              -> 0.35),
          0.7
        )
        assertEquals(set.answers("sev"), answer, "a fractional position is preserved between levels")
        assertEquals(set.score("sev"), Right(answer), "the dynamic score lookup returns the same answer")
      case Left(f)    => fail(s"a score answer was rejected: ${f.path} ${f.detail}")
    }
  }

  // --------------------------------------------------------------------------
  // Property: Every asked question is answered exactly once
  // --------------------------------------------------------------------------

  // spec: question-model — Property: names in the answer set equal names in
  // the question set — none dropped, none invented, none duplicated
  property("every-asked-question-is-answered-exactly-once") {
    forAll(
      for {
        questions <- genQuestionSet
        response  <- genWellFormedResponse(questions)
      } yield (questions, response)
    ) { case (questions, response) =>
      val bucket = questions.size match {
        case 1          => "1"
        case n if n < 6 => "2-5"
        case _          => "6-20"
      }
      classify(true, s"count-$bucket") {
        SystemOne.decodeResponse(questions, None, Json.render(response)) match {
          case Right(set) => set.names == questions.names
          case Left(_)    => false
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // Property: A selected option is always one the caller supplied
  // --------------------------------------------------------------------------

  // spec: question-model — the decoder maps the selected key and the spread
  // through the supplied option set; an out-of-set key is a fit failure
  property("selected-option-is-always-supplied") {
    forAll(
      for {
        choice <- genChoice
        key    <- Gen.oneOf(choice.options.keys.toVector)
        conf   <- Gen.choose(0.0, 1.0)
      } yield (choice, key, conf)
    ) { case (choice, key, conf) =>
      val bucket = choice.options.size match {
        case 1           => "1"
        case n if n < 11 => "2-10"
        case 255         => "255"
        case _           => "11-254"
      }
      classify(true, s"options-$bucket") {
        val probs = choice.options.keys.toVector.map(k => k -> Json.JNumber(BigDecimal(1.0 / choice.options.size)))
        val body  =
          s"""{"model":"m","answers":{"c":{"type":"choice","choice":${Json.render(Json.JString(key))},""" +
            s""""probabilities":${Json.render(Json.JObject(probs))},"confidence":${conf.toString}}}}"""
        SystemOne.decodeResponse(QuestionSet("c" -> choice), None, body) match {
          case Right(set) =>
            set.answers("c") match {
              case a: ChoiceAnswer[?] =>
                a.choice == key && a.probabilities.keySet == choice.options.keySet
              case _                  => false
            }
          case Left(_)    => false
        }
      }
    }
  }

  // spec: question-model — Scenario: An option the enumeration does not
  // contain — a key the supplied option mapping rejects fails, naming the
  // entry, and no partial answer set is returned
  test("an option outside the supplied set fails, naming the entry") {
    val options = ListMap("billing" -> Entry.text("Billing"), "technical" -> Entry.text("Technical"))
    // the supplied option mapping admits exactly the option set — a key
    // outside it is rejected, which is what the derived choice builds
    val choice  = Choice[String](Entry.text("Which team?"), options, k => Option.when(options.contains(k))(k))
    val qs      = QuestionSet("department" -> choice, "other" -> Noul(Entry.text("n?")))
    val body    =
      """{"model":"m","answers":{"department":{"type":"choice","choice":"sales","probabilities":{"billing":1.0},"confidence":0.5},""" +
        """"other":{"type":"noul","noul":0.5}}}"""
    SystemOne.decodeResponse(qs, None, body) match {
      case Left(f)  => assert(f.path.contains("department"), s"the failure does not name the entry: ${f.path}")
      case Right(_) => fail("an option outside the supplied set produced a partial answer set")
    }
  }

  // spec: question-model — the spread is defined over EXACTLY the supplied
  // set (Ring 8 regression): a wire spread covering only a subset of the
  // options is a violation, not an answer
  test("a choice spread covering only a subset of the supplied options fails, naming the member") {
    val options = ListMap("billing" -> Entry.text("Billing"), "technical" -> Entry.text("Technical"))
    val choice  = Choice[String](Entry.text("Which team?"), options, k => Option.when(options.contains(k))(k))
    val qs      = QuestionSet("department" -> choice)
    val body    =
      """{"model":"m","answers":{"department":{"type":"choice","choice":"billing",""" +
        """"probabilities":{"billing":1.0},"confidence":0.5}}}"""
    SystemOne.decodeResponse(qs, None, body) match {
      case Left(f)  =>
        assert(f.path.contains("department.probabilities"), s"the failure does not name the member: ${f.path}")
        assert(f.detail.contains("spread"), s"the failure does not describe the defect: ${f.detail}")
      case Right(_) => fail("a spread over a subset of the options produced an answer")
    }

    // an empty spread leaves every option uncovered — the failure enumerates
    // the missing members
    val emptyBody =
      """{"model":"m","answers":{"department":{"type":"choice","choice":"billing",""" +
        """"probabilities":{},"confidence":0.5}}}"""
    SystemOne.decodeResponse(qs, None, emptyBody) match {
      case Left(f)  =>
        assert(f.detail.contains("billing") && f.detail.contains(", "), s"the failure does not enumerate the missing options: ${f.detail}")
      case Right(_) => fail("an empty spread produced an answer")
    }
  }

  // spec: question-model — the same over-exactly invariant holds for score:
  // legend and spread are each defined over exactly the declared level numbers
  test("a score legend covering only a subset of the declared levels fails, naming the member") {
    val score = Score[Int](Entry.text("How severe?"), Vector(Entry.text("low"), Entry.text("mid"), Entry.text("high")))
    val qs    = QuestionSet("severity" -> score)
    val body  =
      """{"model":"m","answers":{"severity":{"type":"score","score":0.5,""" +
        """"legend":{"0":"low"},"probabilities":{"0":1.0,"1":0.0,"2":0.0},"confidence":0.5}}}"""
    SystemOne.decodeResponse(qs, None, body) match {
      case Left(f)  =>
        assert(f.path.contains("severity.legend"), s"the failure does not name the member: ${f.path}")
        assert(f.detail.contains("legend") && f.detail.contains(", "), s"the failure does not describe the defect: ${f.detail}")
      case Right(_) => fail("a legend over a subset of the declared levels produced an answer")
    }
  }

  test("a score spread covering only a subset of the declared levels fails, naming the member") {
    val score = Score[Int](Entry.text("How severe?"), Vector(Entry.text("low"), Entry.text("mid"), Entry.text("high")))
    val qs    = QuestionSet("severity" -> score)
    val body  =
      """{"model":"m","answers":{"severity":{"type":"score","score":0.5,""" +
        """"legend":{"0":"low","1":"mid","2":"high"},"probabilities":{"0":1.0},"confidence":0.5}}}"""
    SystemOne.decodeResponse(qs, None, body) match {
      case Left(f)  =>
        assert(f.path.contains("severity.probabilities"), s"the failure does not name the member: ${f.path}")
        assert(f.detail.contains("spread") && f.detail.contains(", "), s"the failure does not describe the defect: ${f.detail}")
      case Right(_) => fail("a spread over a subset of the declared levels produced an answer")
    }
  }

  // --------------------------------------------------------------------------
  // Property: A rubric position lies inside the rubric
  // --------------------------------------------------------------------------

  // spec: question-model — position in [0, levelCount - 1], spread over
  // exactly the declared level numbers
  property("rubric-position-lies-inside-the-rubric") {
    forAll(
      for {
        score   <- genScore
        weights <- Gen.sequence[List[Double], Double](score.levels.indices.map(_ => Gen.choose(0.0, 1.0)).toList)
        conf    <- Gen.choose(0.0, 1.0)
      } yield (score, weights, conf)
    ) { case (score, weights, conf) =>
      val total    = weights.sum
      val probs    = if (total == 0.0) weights.map(_ => 1.0 / score.levels.size) else weights.map(_ / total)
      val position = probs.zipWithIndex.map { case (w, i) => w * i }.sum
      classify(true, s"levels-${score.levels.size}") {
        classify(true, if (position == position.floor) "integral" else "fractional") {
          val legend = score.levels.indices.map(i => i.toString -> score.levels(i).json).toVector
          val pj     = score.levels.indices.map(i => i.toString -> Json.JNumber(BigDecimal(probs(i)))).toVector
          val body   =
            s"""{"model":"m","answers":{"s":{"type":"score","score":${BigDecimal(position)},""" +
              s""""legend":${Json.render(Json.JObject(legend))},"probabilities":${Json.render(Json.JObject(pj))},"confidence":${conf.toString}}}}"""
          SystemOne.decodeResponse(QuestionSet("s" -> score), None, body) match {
            case Right(set) =>
              set.answers("s") match {
                case a: ScoreAnswer[?] =>
                  a.score >= 0.0 && a.score <= score.levels.size - 1 &&
                    a.probabilities.keySet == score.levels.indices.toSet &&
                    a.legend.keySet == score.levels.indices.toSet
                case _                 => false
              }
            case Left(_)    => false
          }
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // Requirement: Choice options may be taken from a domain enumeration
  // --------------------------------------------------------------------------

  enum Department {
    case Billing, Technical, Sales
  }

  // spec: question-model — Scenario: Options taken from an enumeration — the
  // case labels are sent as the option keys and the selected option comes
  // back as an enumeration member
  test("a choice derived from an enumeration sends its case labels") {
    val choice: Choice[Department] = Choice.derived[Department](Entry.text("Which team?"))
    val req                        = SystemOne.renderRequest("state", "m", QuestionSet("department" -> choice))
    req match {
      case Json.JObject(ms) =>
        ms.collectFirst { case ("questions", Json.JObject(qs)) => qs.collectFirst { case ("department", Json.JObject(dm)) => dm } }.flatten match {
          case Some(dm) =>
            assertEquals(
              dm.collectFirst { case ("criteria", Json.JObject(cs)) => cs.map(_._1).toSet },
              Some(Set("Billing", "Technical", "Sales")),
              "the enumeration's case labels are the option keys"
            )
          case None     => fail("the derived choice was not rendered")
        }
      case _                => fail("expected a request object")
    }
    val body                       =
      """{"model":"m","answers":{"department":{"type":"choice","choice":"Technical","probabilities":{"Billing":0.2,"Technical":0.5,"Sales":0.3},"confidence":0.9}}}"""
    SystemOne.decodeResponse(QuestionSet("department" -> choice), None, body) match {
      case Right(set) =>
        assertEquals(
          set.answers("department"),
          ChoiceAnswer[Department](
            Department.Technical,
            Map(Department.Billing -> 0.2, Department.Technical -> 0.5, Department.Sales -> 0.3),
            0.9
          ),
          "the selected option is a member of the enumeration"
        )
      case Left(f)    => fail(s"a derived choice's answer was rejected: ${f.path} ${f.detail}")
    }
  }

  // spec: question-model — Scenario: An option the enumeration does not
  // contain — through the derived choice's decode mapping
  test("an option the enumeration does not contain fails, naming the entry") {
    val choice: Choice[Department] = Choice.derived[Department](Entry.text("Which team?"))
    val body                       =
      """{"model":"m","answers":{"department":{"type":"choice","choice":"billing","probabilities":{"Billing":1.0},"confidence":0.5}}}"""
    SystemOne.decodeResponse(QuestionSet("department" -> choice), None, body) match {
      case Left(f)  => assert(f.path.contains("department"), s"the failure does not name the entry: ${f.path}")
      case Right(_) => fail("'billing' is not a case label of Department yet produced an answer")
    }
  }

  // spec: question-model — task 3.7: Naming is the caller-supplied
  // enum-case → wire-key policy; lowerSnake is the documented transform
  test("a caller-supplied Naming transforms the enum case labels") {
    enum Team {
      case TechSupport, FieldOps
    }
    given Naming             = Naming.lowerSnake
    val choice: Choice[Team] = Choice.derived[Team](Entry.text("Who owns it?"))
    val req                  = SystemOne.renderRequest("state", "m", QuestionSet("team" -> choice))
    req match {
      case Json.JObject(ms) =>
        ms.collectFirst { case ("questions", Json.JObject(qs)) => qs.collectFirst { case ("team", Json.JObject(tm)) => tm } }.flatten match {
          case Some(tm) =>
            assertEquals(
              tm.collectFirst { case ("criteria", Json.JObject(cs)) => cs.map(_._1).toSet },
              Some(Set("tech_support", "field_ops")),
              "the wire keys are the transformed labels"
            )
          case None     => fail("the derived choice was not rendered")
        }
      case _                => fail("expected a request object")
    }
  }

  // --------------------------------------------------------------------------
  // Requirement: A question set written in source yields a matching answer set
  // --------------------------------------------------------------------------

  // spec: question-model — Scenario: Request keys come from the names already
  // written — the field names are the wire keys; the caller never writes them
  // a second time
  test("request keys come from the names already written") {
    val questions = (
      refundRequested = Noul(Entry.text("Refund?")),
      department = Choice.of[("billing", "technical")](Entry.text("Which team?")),
      severity = Score.of(Entry.text("How severe?"))(("low", "mid", "high"))
    )
    SystemOne.renderTyped("the customer wants a refund", "jev-latest")(questions) match {
      case Right(req) =>
        req match {
          case Json.JObject(ms) =>
            assertEquals(
              ms.collectFirst { case ("questions", Json.JObject(qs)) => qs.map(_._1).toSet },
              Some(Set("refundRequested", "department", "severity")),
              "the wire keys are the field names"
            )
            assertEquals(
              Right(req),
              SystemOne.renderDynamic("the customer wants a refund", "jev-latest")(
                List(
                  "refundRequested" -> (questions.refundRequested: Question[?]),
                  "department"      -> (questions.department: Question[?]),
                  "severity"        -> (questions.severity: Question[?])
                )
              ),
              "the same set assembled at run time renders the identical request"
            )
          case _                => fail("expected a request object")
        }
      case Left(f)    => fail(s"a source-written set was refused: ${f.detail}")
    }
  }

  // spec: question-model — Scenario: Names and shapes are both carried
  // through — decodeTyped answers are addressed by field name and shaped by
  // AnswerOf (the static types in this test ARE the witness: assigning them
  // to narrower-typed vals would fail to compile if the shapes were wrong)
  test("names and shapes are both carried through") {
    val questions = (
      refundRequested = Noul(Entry.text("Refund?")),
      department = Choice.of[("billing", "technical")](Entry.text("Which team?"))
    )
    val body      =
      """{"model":"jev-latest","answers":{""" +
        """"refundRequested":{"type":"noul","noul":0.9},""" +
        """"department":{"type":"choice","choice":"billing","probabilities":{"billing":0.7,"technical":0.3},"confidence":0.8}}}"""
    SystemOne.decodeTyped(questions, RequestId.fromHeader("req-9"), body) match {
      case Right(a) =>
        val refund: NoulAnswer                          = a.refundRequested
        val dept: ChoiceAnswer["billing" | "technical"] = a.department
        assertEquals(refund, NoulAnswer(0.9))
        assertEquals(dept: Answer, ChoiceAnswer("billing", Map("billing" -> 0.7, "technical" -> 0.3), 0.8))
      case Left(f)  => fail(s"a well-formed response was rejected: ${f.getMessage}")
    }
  }

  // spec: question-model — Scenario: A selected option can be exhausted over —
  // the union-typed `choice` refines so a match needs exactly the options;
  // omitting one is an E029 warning escalated by -Werror (living witness —
  // the -Werror build IS the check)
  test("a selected option can be exhausted over") {
    val questions = (department = Choice.of[("billing", "technical")](Entry.text("Which team?")))
    val body      =
      """{"model":"m","answers":{"department":{"type":"choice","choice":"technical","probabilities":{"billing":0.3,"technical":0.7},"confidence":0.6}}}"""
    SystemOne.decodeTyped(questions, None, body) match {
      case Right(a) =>
        val handled = a.department.choice match {
          case "billing"   => "billed"
          case "technical" => "escalated"
        }
        assertEquals(handled, "escalated")
      case Left(f)  => fail(s"a well-formed response was rejected: ${f.getMessage}")
    }
  }

  // spec: question-model — request-id propagation, re-verified on the typed
  // surface: a decode failure through decodeTyped carries the caller-supplied
  // trace, exactly as decodeResponse does (success-path propagation lives on
  // AnswerSet, verified by wire-codec)
  test("the caller-supplied trace is carried on the typed failure side") {
    val questions = (refundRequested = Noul(Entry.text("Refund?")))
    val trace     = RequestId.fromHeader("req-typed-1")
    SystemOne.decodeTyped(questions, trace, """{"model":"m","answers":{"refundRequested":{"type":"noul","noul":"high"}}}""") match {
      case Left(f: TypesafeException.ResponseValidation) =>
        assertEquals(f.requestId, trace, "the trace is carried on the typed failure side")
      case other                                         => fail(s"expected a response-validation failure carrying the trace, got $other")
    }
  }

  // spec: question-model — ONE decoder serves both surfaces: decodeTyped's
  // per-name answers equal decodeResponse's AnswerSet entries on the same body
  test("the typed and dynamic surfaces decode through one decoder") {
    val questions = (
      refundRequested = Noul(Entry.text("Refund?")),
      severity = Score.of(Entry.text("How severe?"))(("low", "high"))
    )
    val body      =
      """{"model":"m","answers":{""" +
        """"refundRequested":{"type":"noul","noul":0.4},""" +
        """"severity":{"type":"score","score":0.6,"legend":{"0":"low","1":"high"},"probabilities":{"0":0.6,"1":0.4},"confidence":0.7}}}"""
    val dynamic   = QuestionSet(
      "refundRequested" -> (questions.refundRequested: Question[?]),
      "severity"        -> (questions.severity: Question[?])
    )
    (SystemOne.decodeTyped(questions, None, body), SystemOne.decodeResponse(dynamic, None, body)) match {
      case (Right(typed), Right(set)) =>
        assertEquals(typed.refundRequested: Answer, set.answers("refundRequested"))
        assertEquals(typed.severity: Answer, set.answers("severity"))
      case other                      => fail(s"the two surfaces disagree: $other")
    }
  }

  // --------------------------------------------------------------------------
  // Property: Both call surfaces form the same request
  // --------------------------------------------------------------------------

  // spec: question-model — genQuestionSet restricted to a fixed-arity shape
  // expressible as a literal named tuple: three generated questions rendered
  // through each surface produce byte-identical requests
  property("both-surfaces-form-the-same-request") {
    forAll(
      for {
        q0 <- genQuestion
        q1 <- genQuestion
        q2 <- genQuestion
      } yield (q0, q1, q2)
    ) { case (q0, q1, q2) =>
      val kinds = Set(q0, q1, q2).map {
        case _: Noul      => "noul"
        case _: Choice[?] => "choice"
        case _: Score[?]  => "score"
      }
      classify(true, s"arity-3-${if (kinds.size == 1) "uniform" else "mixed"}") {
        val named   = (a = q0, b = q1, c = q2)
        val typed   = SystemOne.renderTyped("state", "m")(named)
        val dynamic = SystemOne.renderDynamic("state", "m")(
          List("a" -> q0, "b" -> q1, "c" -> q2)
        )
        (typed, dynamic) match {
          case (Right(t), Right(d)) => Json.render(t) == Json.render(d)
          case _                    => false
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // Requirement: Documented limits are refused before a request is made —
  // the runtime half
  // --------------------------------------------------------------------------

  // spec: question-model — Scenario: A limit broken only at run time still
  // fails safely — validated names the offending question and no request is
  // formed (renderDynamic is the submission gate)
  test("runtime-limit-fails-before-request") {
    val overOptions = Choice[String](
      Entry.text("Too many?"),
      ListMap.from((1 to 256).map(i => s"opt$i" -> Entry.text(s"option $i"))),
      Some(_)
    )
    QuestionSet.validated(List("big" -> overOptions)) match {
      case Left(TypesafeException.InvalidQuestion(name, detail)) =>
        assertEquals(name, Some("big"), "the failure names the offending question")
        assert(detail.nonEmpty, "the failure says nothing about which limit broke")
      case other                                                 => fail(s"a 256-option choice was accepted: $other")
    }

    val tooManyLevels = Score[Int](Entry.text("Rubric?"), Vector.fill(11)(Entry.text("level")))
    QuestionSet.validated(List("rubric" -> tooManyLevels)) match {
      case Left(TypesafeException.InvalidQuestion(name, detail)) =>
        assertEquals(name, Some("rubric"))
        assert(detail.nonEmpty, "the failure says nothing about which limit broke")
      case other                                                 => fail(s"an 11-level score was accepted: $other")
    }

    val tooFewLevels = Score[Int](Entry.text("Rubric?"), Vector(Entry.text("only")))
    QuestionSet.validated(List("thin" -> tooFewLevels)) match {
      case Left(TypesafeException.InvalidQuestion(name, detail)) =>
        assertEquals(name, Some("thin"))
        assert(detail.nonEmpty, "the failure says nothing about which limit broke")
      case other                                                 => fail(s"a 1-level score was accepted: $other")
    }

    // an optionless choice fails the same gate — and the member's message is readable
    val emptyChoice = Choice[String](Entry.text("Empty?"), ListMap.empty, Some(_))
    QuestionSet.validated(List("empty" -> emptyChoice)) match {
      case Left(f: TypesafeException.InvalidQuestion) =>
        assertEquals(f.name, Some("empty"))
        assert(f.detail.nonEmpty, "the failure says nothing about which limit broke")
        assert(f.getMessage != null && !f.getMessage.isBlank, "the member's message is unreadable")
      case other                                      => fail(s"an optionless choice was accepted: $other")
    }

    // an empty set is a set-level failure — there is no question to name
    QuestionSet.validated(List.empty) match {
      case Left(f: TypesafeException.InvalidQuestion) =>
        assertEquals(f.name, None, "an empty set has no question to name")
        assert(f.detail.nonEmpty, "the failure says nothing about what is wrong")
        assert(f.getMessage != null && !f.getMessage.isBlank, "the member's message is unreadable")
      case other                                      => fail(s"an empty question set was accepted: $other")
    }

    // the submission gate: renderDynamic on an over-limit set fails the same
    // way — before any request could be formed
    SystemOne.renderDynamic("state", "m")(List("big" -> overOptions)) match {
      case Left(TypesafeException.InvalidQuestion(name, _)) => assertEquals(name, Some("big"))
      case other                                            => fail(s"renderDynamic formed a request for a 256-option choice: $other")
    }

    // a valid runtime set passes the gate and renders identically to the
    // spec-2 codec path
    val valid =
      List("a" -> (Noul(Entry.text("n?")): Question[?]), "b" -> (Score[2](Entry.text("s?"), Vector(Entry.text("l"), Entry.text("h"))): Question[?]))
    (QuestionSet.validated(valid), SystemOne.renderDynamic("state", "m")(valid)) match {
      case (Right(qs), Right(json)) =>
        assertEquals(json, SystemOne.renderRequest("state", "m", qs), "the dynamic surface renders through the same codec")
      case other                    => fail(s"a valid runtime set was refused: $other")
    }
  }

  // --------------------------------------------------------------------------
  // Requirement: Question sets built at run time are equally supported
  // --------------------------------------------------------------------------

  // spec: question-model — Scenario: One question per retrieved item — a set
  // assembled at run time is validated, asked, and every answer is addressed
  // by its own name
  test("one question per retrieved item") {
    val entries   = (1 to 30).map(i => s"item$i" -> (Noul(Entry.text(s"Does item $i qualify?")): Question[?])).toList
    val questions = QuestionSet.validated(entries).fold(f => fail(s"a valid runtime set was refused: ${f.detail}"), identity)
    val body      =
      "{\"model\":\"m\",\"answers\":{" +
        entries.map { case (name, _) => s"\"$name\":{\"type\":\"noul\",\"noul\":0.5}" }.mkString(",") +
        "}}"
    SystemOne.decodeResponse(questions, None, body) match {
      case Right(set) =>
        assertEquals(set.names, entries.map(_._1).toSet, "every one of the thirty answers is addressed by its own name")
        entries.foreach { case (name, _) =>
          assertEquals(set.noul(name), Right(NoulAnswer(0.5)), s"answer '$name' is not readable through the kind-indexed lookup")
        }
      case Left(f)    => fail(s"a runtime-built set's answers were rejected: ${f.path} ${f.detail}")
    }
  }

  // spec: question-model — Scenario: Reading a name that was never asked —
  // a failure value naming the name, never a raise
  test("reading a name that was never asked yields a failure value") {
    val qs  = QuestionSet("refundRequested" -> Noul(Entry.text("Refund?")))
    val set = decodeNoul(qs)
    set.noul("escalation") match {
      case Left(TypesafeException.MissingAnswer(name)) =>
        assertEquals(name, "escalation", "the failure names the name that was read")
      case other                                       => fail(s"an unasked name produced $other")
    }
    assert(set.choice("escalation").isLeft, "a kind lookup on an unasked name succeeds")
    assert(set.score("escalation").isLeft, "a kind lookup on an unasked name succeeds")
  }

  // spec: question-model — Scenario: Reading an answer as the wrong kind —
  // a failure value, never a raise
  test("reading an answer as the wrong kind yields a failure value") {
    val qs   = QuestionSet(
      "department" -> Choice[String](Entry.text("Which team?"), ListMap("x" -> Entry.text("X"), "y" -> Entry.text("Y")), Some(_)),
      "severity"   -> Score[2](Entry.text("s?"), Vector(Entry.text("l"), Entry.text("h")))
    )
    val body =
      """{"model":"m","answers":{"department":{"type":"choice","choice":"x","probabilities":{"x":0.6,"y":0.4},"confidence":0.5},""" +
        """"severity":{"type":"score","score":0.3,"legend":{"0":"l","1":"h"},"probabilities":{"0":0.7,"1":0.3},"confidence":0.9}}}"""
    val set  = SystemOne.decodeResponse(qs, None, body).fold(f => fail(s"setup decode failed: ${f.path} ${f.detail}"), identity)
    assert(set.noul("department").isLeft, "a choice answer was read as a noul")
    assert(set.score("department").isLeft, "a choice answer was read as a score")
    assert(set.noul("severity").isLeft, "a score answer was read as a noul")
    assert(set.choice("severity").isLeft, "a score answer was read as a choice")
    // and the right kind still succeeds — the lookups are not merely pessimistic
    assert(set.choice("department").isRight, "a choice answer was not readable as a choice")
    assert(set.score("severity").isRight, "a score answer was not readable as a score")
  }

  private def decodeNoul(questions: QuestionSet): AnswerSet =
    SystemOne
      .decodeResponse(questions, None, """{"model":"m","answers":{"refundRequested":{"type":"noul","noul":0.9}}}""")
      .fold(f => fail(s"setup decode failed: ${f.path} ${f.detail}"), identity)
}
