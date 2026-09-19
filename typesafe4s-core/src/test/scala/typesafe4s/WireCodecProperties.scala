package typesafe4s

import scala.collection.immutable.ListMap
import scala.io.Source

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.{classify, forAll}

import typesafe4s.json.Json

// ============================================================================
// TEST ORACLE — spec: wire-codec (change: add-typesafe4s-sdk, spec 2/8)
//
// Written from the spec and the Gate-1 contract BEFORE any implementation.
// Every test cites its spec source. Generators are constructive — ScalaCheck's
// classify is informational only, so interesting cases are reached by
// construction, never by rare draws.
// ============================================================================
class WireCodecProperties extends ScalaCheckSuite {

  // --------------------------------------------------------------------------
  // Generators
  // --------------------------------------------------------------------------

  // spec: wire-codec — Property: Rendering then reading is the identity
  // genJson — constructive, depth-bounded (cap 4). The text alphabet includes
  // quote, backslash, newline, control characters and non-ASCII by
  // construction, so escaping is exercised on nearly every run.
  private val genTextChar: Gen[Char] = Gen.frequency(
    6 -> Gen.alphaNumChar,
    4 -> Gen.oneOf('"', '\\', '\n', '\r', '\t', '\b', '\f'),
    2 -> Gen.choose(0x00.toChar, 0x1f.toChar),  // other control characters
    3 -> Gen.choose(0x80.toChar, 0x2fff.toChar) // non-ASCII (BMP, below surrogates)
  )
  private val genText: Gen[String]   = Gen.listOf(genTextChar).map(_.mkString)

  private def genJson(depth: Int): Gen[Json] = {
    val leaf: Gen[Json] = Gen.frequency(
      1 -> Gen.const(Json.JNull),
      1 -> Gen.oneOf(true, false).map(Json.JBool(_)),
      // integers and fractions, small and large; BigDecimal keeps them exact
      2 -> Gen
        .oneOf(
          Gen.choose(Long.MinValue / 2, Long.MaxValue / 2).map(BigDecimal(_)),
          Gen.choose(-1e15, 1e15).map(BigDecimal(_)),
          Gen.choose(-1.0, 1.0).map(BigDecimal(_))
        )
        .map(Json.JNumber(_)),
      4 -> genText.map(Json.JString(_))
    )
    if (depth <= 0) leaf
    else
      Gen.frequency(
        5 -> leaf,
        2 -> Gen.choose(0, 4).flatMap(n => Gen.listOfN(n, genJson(depth - 1))).map(vs => Json.JArray(vs.toVector)),
        2 -> Gen
          .choose(0, 6)
          .flatMap(n => Gen.listOfN(n, Gen.zip(genMemberName, genJson(depth - 1))))
          .map(ms => Json.JObject(distinctKeys(ms).toVector))
      )
  }

  private val genMemberName: Gen[String] = genText

  // object members hold DISTINCT keys — first occurrence wins on a collision,
  // so a generated object's members are unique by construction
  private def distinctKeys[A](ms: List[(String, A)]): List[(String, A)] =
    ms.foldLeft(List.empty[(String, A)]) { (acc, m) =>
      if (acc.exists(_._1 == m._1)) acc else acc :+ m
    }

  // entry-shaped Json — the recursive text/object/array/nothing shapes (D1);
  // used for instructions, meanings, descriptions and levels
  private def genEntryJson(depth: Int): Gen[Json] =
    if (depth <= 0) Gen.frequency(1 -> Gen.const(Json.JNull), 4 -> genText.map(Json.JString(_)))
    else
      Gen.frequency(
        4                           -> Gen.frequency(1 -> Gen.const(Json.JNull), 4 -> genText.map(Json.JString(_))),
        1                           -> Gen.choose(0, 3).flatMap(n => Gen.listOfN(n, genEntryJson(depth - 1))).map(vs => Json.JArray(vs.toVector)),
        1                           -> Gen
          .choose(0, 3)
          .flatMap(n => Gen.listOfN(n, Gen.zip(genMemberName, genEntryJson(depth - 1))))
          .map(ms => Json.JObject(distinctKeys(ms).toVector))
      )

  // genEntryJson produces only entry shapes by construction, so fromJson
  // never rejects — a Left here means the GENERATOR is broken, not the codec
  private val genEntry: Gen[Entry] =
    genEntryJson(2).map(j => Entry.fromJson(j).fold(p => throw new IllegalStateException(s"genEntryJson produced a non-entry shape at $p"), identity))

  // --------------------------------------------------------------------------
  // Question/response generators — shared shape with question-model (spec 3)
  // --------------------------------------------------------------------------

  // spec: wire-codec — Property: Every asked question appears in the request
  // exactly once. genQuestionSet — constructive; names are drawn as q1..qn so
  // distinctness cannot fail; 1-20 questions of mixed kinds.
  private val genQuestion: Gen[Question[?]] = Gen.frequency(
    3 -> (for {
      instr <- genEntry
      crit  <- Gen.option(Gen.zip(genEntry, genEntry).map { case (y, n) => Noul.Criteria(y, n) })
    } yield Noul(instr, crit)),
    3 -> (for {
      instr <- genEntry
      nOpts <- Gen.choose(1, 8)
      opts  <- Gen.listOfN(nOpts, Gen.zip(Gen.choose(1, 999).map(i => s"opt$i"), genEntry))
    } yield Choice[String](instr, ListMap.from(distinctKeys(opts)), Some(_))),
    3 -> (for {
      instr   <- genEntry
      nLevels <- Gen.choose(2, 10) // documented range
      levels  <- Gen.listOfN(nLevels, genEntry)
    } yield Score(instructions = instr, levels = levels.toVector))
  )

  private val genQuestionSet: Gen[QuestionSet] =
    Gen.choose(1, 20).flatMap { n =>
      Gen.listOfN(n, genQuestion).map { qs =>
        val entries = qs.zipWithIndex.map { case (q, i) => s"q${i + 1}" -> (q: Question[?]) }
        QuestionSet(entries.head, entries.tail*)
      }
    }

  // spec: wire-codec — Property: Reading is total over well-formed responses
  // genResponseFor — builds a well-formed response FROM the generated question
  // set, so kind markers and question names agree by construction.
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
        for {
          score <- Gen.choose(0.0, (s.levels.size - 1).toDouble)
          conf  <- Gen.choose(0.0, 1.0)
        } yield {
          val legend: Vector[(String, Json)] = s.levels.indices.map(i => i.toString -> s.levels(i).json).toVector
          val probs: Vector[(String, Json)]  =
            s.levels.indices.map(i => i.toString -> Json.JNumber(BigDecimal(1.0 / s.levels.size))).toVector
          name -> Json.JObject(
            Vector(
              "type"          -> Json.JString("score"),
              "score"         -> Json.JNumber(BigDecimal(score)),
              "legend"        -> Json.JObject(legend),
              "probabilities" -> Json.JObject(probs),
              "confidence"    -> Json.JNumber(BigDecimal(conf))
            )
          )
        }
    }

  // usage present-full / present-partial / absent — by construction
  private val genUsageJson: Gen[Option[Json]] = Gen.oneOf(
    Gen.zip(Gen.choose(0L, 100000L), Gen.choose(0L, 100000L)).map { case (i, o) =>
      Some(Json.JObject(Vector("input_tokens" -> Json.JNumber(BigDecimal(i)), "output_tokens" -> Json.JNumber(BigDecimal(o)))))
    },
    Gen.choose(0L, 100000L).map(i => Some(Json.JObject(Vector("input_tokens" -> Json.JNumber(BigDecimal(i)))))),
    Gen.const(None)
  )

  private def genWellFormedResponse(questions: QuestionSet): Gen[Json] =
    for {
      answers <- Gen.sequence[List[(String, Json)], (String, Json)](
                   questions.entries.toList.map { case (name, q) => genAnswerFor(name, q) }
                 )
      usage   <- genUsageJson
      model   <- Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)
    } yield Json.JObject(
      Vector(
        "model"   -> Json.JString(model),
        "answers" -> Json.JObject(answers.toVector)
      ) ++ usage.map(u => "usage" -> u).toVector
    )

  // one randomly chosen perturbation, guaranteed non-fitting: drop a required
  // member, change a marker, or change a required field's type
  private def perturb(response: Json, questions: QuestionSet, seed: Int): Json = {
    val names                                                                = questions.entries.keys.toVector
    val answerObjs                                                           = response match {
      case Json.JObject(ms) => ms.collectFirst { case ("answers", Json.JObject(as)) => as }.getOrElse(Vector.empty)
      case _                => Vector.empty
    }
    def mapAnswer(f: Vector[(String, Json)] => Vector[(String, Json)]): Json = response match {
      case Json.JObject(ms) =>
        Json.JObject(ms.map { case ("answers", Json.JObject(as)) => "answers" -> Json.JObject(f(as)); case m => m })
      case other            => other
    }
    seed % 4 match {
      // drop one whole answer — the name then has no answer
      case 0 => mapAnswer(as => as.filterNot(_._1 == names(seed % names.size)))
      // drop a required member of one answer
      case 1 =>
        mapAnswer(as =>
          as.map {
            case (n, Json.JObject(ms)) if n == names((seed / 4) % names.size) =>
              n -> Json.JObject(if (ms.size > 1) ms.tail else ms)
            case m                                                            => m
          }
        )
      // change a marker to an unknown kind
      case 2 =>
        mapAnswer(as =>
          as.map {
            case (n, Json.JObject(ms)) if n == names((seed / 8) % names.size) =>
              n -> Json.JObject(ms.map { case ("type", _) => "type" -> Json.JString("fuzzy"); case m => m })
            case m                                                            => m
          }
        )
      // change a required field's type (a number becomes a string)
      case _ =>
        mapAnswer(as =>
          as.map {
            case (n, Json.JObject(ms)) if n == names((seed / 16) % names.size) =>
              n -> Json.JObject(ms.map { case (k, Json.JNumber(v)) => k -> Json.JString(v.toString); case m => m })
            case m                                                             => m
          }
        )
    }
  }

  // --------------------------------------------------------------------------
  // Requirement: The core carries no third-party runtime dependency
  // --------------------------------------------------------------------------

  // spec: wire-codec — Scenario: The published dependency list is empty
  // Discharged by a BUILD assertion (tier 4 — a module's dependency list is a
  // build fact no test can observe): `checkCoreDependencies` in build.sbt
  // asserts `core / Compile / externalDependencyClasspath` holds only
  // org.scala-lang jars, and is wired into `sbt check` so every lint run
  // re-verifies it. The per-adapter assertions land with the adapter modules
  // (task §11 — carry-forward).

  // --------------------------------------------------------------------------
  // Requirement: A rendered value can always be read back
  // --------------------------------------------------------------------------

  // spec: wire-codec — Property: Rendering then reading is the identity
  property("render-then-parse-is-identity") {
    forAll(genJson(4)) { value =>
      val shape = value match {
        case Json.JNull      => "null"
        case _: Json.JBool   => "bool"
        case _: Json.JNumber => "number"
        case _: Json.JString => "string"
        case _: Json.JArray  => "array"
        case _: Json.JObject => "object"
      }
      classify(true, shape) {
        Json.parse(Json.render(value)) == Right(value)
      }
    }
  }

  // spec: wire-codec — Scenario: A value survives the round trip
  test("a value survives the round trip") {
    val value = Json.JObject(
      Vector(
        "nested"  -> Json.JObject(Vector("a" -> Json.JArray(Vector(Json.JNumber(1), Json.JNull)))),
        "text"    -> Json.JString("hello \"world\"\nbye"),
        "number"  -> Json.JNumber(BigDecimal("3.14")),
        "nothing" -> Json.JNull
      )
    )
    assertEquals(Json.parse(Json.render(value)), Right(value))
  }

  // spec: wire-codec — Scenario: Member order is not rearranged
  test("member order is not rearranged") {
    val value = Json.JObject(Vector("z" -> Json.JNumber(1), "a" -> Json.JNumber(2), "m" -> Json.JNumber(3)))
    assertEquals(Json.render(value), """{"z":1,"a":2,"m":3}""")
  }

  // spec: wire-codec — Scenario: Text that needs escaping survives
  test("text that needs escaping survives") {
    val value   = Json.JString("a \"quote\" and a \\backslash\\ and a\nnewline and é and λ and €")
    assertEquals(Json.parse(Json.render(value)), Right(value))
    val control = Json.JString("")
    assertEquals(Json.parse(Json.render(control)), Right(control))
  }

  // spec: wire-codec — a body is JSON before it is a shape: insignificant
  // whitespace anywhere the grammar allows it must read back (Ring-8 D1:
  // every input the oracle parses is produced by the compact renderer, so
  // post-colon whitespace was never exercised until this test)
  test("insignificant whitespace is skipped") {
    val body = """{ "a" : 1, "b" : [ true , null ], "c" :{ "d" :	"x" } }"""
    assertEquals(
      Json.parse(body),
      Right(
        Json.JObject(
          Vector(
            "a" -> Json.JNumber(1),
            "b" -> Json.JArray(Vector(Json.JBool(true), Json.JNull)),
            "c" -> Json.JObject(Vector("d" -> Json.JString("x")))
          )
        )
      )
    )
    val qs   = QuestionSet("a" -> Noul(Entry.text("n?")))
    SystemOne.decodeResponse(qs, None, """{ "model" : "m" , "answers" : { "a" : { "type" : "noul" , "noul" : 0.5 } } }""") match {
      case Right(set) => assertEquals(set.answers("a"), NoulAnswer(0.5))
      case Left(f)    => fail(s"a pretty-printed response was rejected: ${f.path} ${f.detail}")
    }
  }

  // spec: wire-codec — Property: Reading is total — it never raises: a body
  // nested past the depth bound is a body that does not fit, not a stack
  // overflow escaping the failure channel (Ring-8 D3). The bound (100) sits
  // far above real payloads yet keeps the parser's own recursion safe on a
  // minimal thread stack.
  test("a body nested past the depth bound is rejected, not raised") {
    val deep = "[" * 1100 + "]" * 1100
    Json.parse(deep) match {
      case Left(_)  => ()
      case Right(_) => fail("a body nested past the bound was accepted")
    }
    val qs   = QuestionSet("a" -> Noul(Entry.text("n?")))
    SystemOne.decodeResponse(qs, None, deep) match {
      case Left(f)  => assert(f.path.nonEmpty, "the failure names no path")
      case Right(_) => fail("a body nested past the bound produced an answer set")
    }
  }

  // --------------------------------------------------------------------------
  // Requirement: A request carries the shape each question kind requires
  // --------------------------------------------------------------------------

  // spec: wire-codec — Property: Every asked question appears in the request
  // exactly once
  property("every-asked-question-appears-once") {
    forAll(genQuestionSet) { questions =>
      SystemOne.renderRequest("state", "m", questions) match {
        case Json.JObject(members) =>
          members.collectFirst { case ("questions", Json.JObject(qs)) => qs.map(_._1).toSet } match {
            case Some(rendered) => rendered == questions.names
            case None           => false
          }
        case _                     => false
      }
    }
  }

  // spec: wire-codec — Scenario: A yes/no question on the wire
  test("a yes/no question on the wire") {
    val q   = Noul(Entry.text("Does this message express urgency?"))
    val req = SystemOne.renderRequest("state", "jev-latest", QuestionSet("urgency" -> q))
    questionMember(req, "urgency") match {
      case Some(Json.JObject(ms)) =>
        assertEquals(ms.collectFirst { case ("type", Json.JString(t)) => t }, Some("noul"))
        assertEquals(ms.collectFirst { case ("instructions", j) => j }, Some(Entry.text("Does this message express urgency?").json))
        assert(ms.forall(_._1 != "criteria"), "a noul without meanings carries no criteria member at all")
      case other                  => fail(s"expected a question member, got $other")
    }
  }

  // spec: wire-codec — Scenario: A yes/no question with meanings supplied
  test("a yes/no question with meanings supplied") {
    val q   = Noul(Entry.text("Refund?"), Some(Noul.Criteria(Entry.text("asks for money back"), Entry.text("no refund ask"))))
    val req = SystemOne.renderRequest("state", "jev-latest", QuestionSet("refund" -> q))
    questionMember(req, "refund") match {
      case Some(Json.JObject(ms)) =>
        ms.collectFirst { case ("criteria", Json.JObject(cs)) => cs } match {
          case Some(cs) =>
            assertEquals(cs.collectFirst { case ("true", j) => j }, Some(Entry.text("asks for money back").json))
            assertEquals(cs.collectFirst { case ("false", j) => j }, Some(Entry.text("no refund ask").json))
          case None     => fail("meanings were supplied but no criteria member was rendered")
        }
      case other                  => fail(s"expected a question member, got $other")
    }
  }

  // spec: wire-codec — Scenario: A closed-set question on the wire
  test("a closed-set question on the wire") {
    val q   = Choice[String](
      Entry.text("Which team?"),
      ListMap("billing" -> Entry.text("Billing"), "technical" -> Entry.nothing, "sales" -> Entry.text("Sales")),
      Some(_)
    )
    val req = SystemOne.renderRequest("state", "jev-latest", QuestionSet("team" -> q))
    questionMember(req, "team") match {
      case Some(Json.JObject(ms)) =>
        ms.collectFirst { case ("criteria", Json.JObject(cs)) => cs } match {
          case Some(cs) =>
            assertEquals(cs.toMap.keySet, Set("billing", "technical", "sales"))
            assertEquals(cs.collectFirst { case ("technical", j) => j }, Some(Json.JNull), "a no-description option maps to nothing")
          case None     => fail("a choice carried no criteria member")
        }
      case other                  => fail(s"expected a question member, got $other")
    }
  }

  // spec: wire-codec — Scenario: A rubric question on the wire
  test("a rubric question on the wire") {
    val q   = Score[4](
      Entry.text("How severe?"),
      Vector(Entry.text("none"), Entry.text("minor"), Entry.text("major"), Entry.text("critical"))
    )
    val req = SystemOne.renderRequest("state", "jev-latest", QuestionSet("severity" -> q))
    questionMember(req, "severity") match {
      case Some(Json.JObject(ms)) =>
        ms.collectFirst { case ("criteria", Json.JArray(vs)) => vs } match {
          case Some(vs) =>
            assertEquals(
              vs,
              Vector(Entry.text("none").json, Entry.text("minor").json, Entry.text("major").json, Entry.text("critical").json),
              "criteria are an ordered list of the level descriptions, in declared order"
            )
          case other    => fail(s"expected criteria as an ordered array, got $other")
        }
      case other                  => fail(s"expected a question member, got $other")
    }
  }

  // spec: wire-codec — Scenario: The model is always named
  test("the model is always named") {
    val req = SystemOne.renderRequest("state", "jev-latest", QuestionSet("q" -> Noul(Entry.text("x?"))))
    req match {
      case Json.JObject(ms) =>
        assertEquals(ms.collectFirst { case ("model", Json.JString(m)) => m }, Some("jev-latest"))
      case other            => fail(s"expected a request object, got $other")
    }
  }

  // spec: wire-codec — Scenario: Structure is sent as structure
  test("structure is sent as structure") {
    val structured = Entry.obj("rule" -> Entry.text("be strict"), "flags" -> Entry.arr(Entry.text("a"), Entry.text("b")))
    val req        = SystemOne.renderRequest("state", "m", QuestionSet("q" -> Noul(structured)))
    questionMember(req, "q") match {
      case Some(Json.JObject(ms)) =>
        ms.collectFirst { case ("instructions", j) => j } match {
          case Some(Json.JObject(_)) => () // carried as an object, not flattened to a string
          case other                 => fail(s"structured instructions were not carried as an object: $other")
        }
      case other                  => fail(s"expected a question member, got $other")
    }
  }

  private def questionMember(request: Json, name: String): Option[Json] = request match {
    case Json.JObject(ms) =>
      ms.collectFirst { case ("questions", Json.JObject(qs)) => qs.collectFirst { case (`name`, j) => j } }.flatten
    case _                => None
  }

  // --------------------------------------------------------------------------
  // Requirement: A response that does not fit is rejected where it broke
  // --------------------------------------------------------------------------

  // lazy so each test fails individually pre-implementation (the contract's
  // ??? bodies throw NotImplementedError) rather than collapsing the suite
  // into one initializationError
  private lazy val mixedQuestions = QuestionSet(
    "a" -> Noul(Entry.text("n?")),
    "b" -> Choice[String](Entry.text("c?"), ListMap("x" -> Entry.text("X"), "y" -> Entry.text("Y")), Some(_)),
    "c" -> Score[2](Entry.text("s?"), Vector(Entry.text("low"), Entry.text("high")))
  )

  private val mixedBody =
    """{"model":"jev-latest","answers":{""" +
      """"a":{"type":"noul","noul":0.9},""" +
      """"b":{"type":"choice","choice":"x","probabilities":{"x":0.7,"y":0.3},"confidence":0.8},""" +
      """"c":{"type":"score","score":0.4,"legend":{"0":"low","1":"high"},"probabilities":{"0":0.6,"1":0.4},"confidence":0.6}""" +
      """},"usage":{"input_tokens":312}}"""

  // spec: wire-codec — Scenario: A mixed answer set is read
  test("a mixed answer set is read") {
    val trace = RequestId.fromHeader("req-7")
    SystemOne.decodeResponse(mixedQuestions, trace, mixedBody) match {
      case Right(set) =>
        assertEquals(set.answers("a"), NoulAnswer(0.9))
        assertEquals(set.answers("b"), ChoiceAnswer("x", Map("x" -> 0.7, "y" -> 0.3), 0.8))
        assertEquals(
          set.answers("c"),
          ScoreAnswer(0.4, Map(0 -> Entry.text("low"), 1 -> Entry.text("high")), Map(0 -> 0.6, 1 -> 0.4), 0.6)
        )
        assertEquals(set.model, "jev-latest", "the model that answered is available")
        assertEquals(set.usage, Usage(Some(312), None), "token counts are independently absent")
        assertEquals(set.requestId, trace, "the caller-supplied trace is carried on the success side")
      case Left(f)    => fail(s"a well-formed mixed response was rejected at '${f.path}': ${f.detail}")
    }
  }

  // spec: wire-codec — Scenario: Token counts may be missing
  test("token counts may be missing") {
    val body = """{"model":"m","answers":{"a":{"type":"noul","noul":0.5}}}"""
    val qs   = QuestionSet("a" -> Noul(Entry.text("n?")))
    SystemOne.decodeResponse(qs, None, body) match {
      case Right(set) =>
        assertEquals(set.usage, Usage(None, None), "unreported counts are reported as absent")
      case Left(f)    => fail(s"a response with no usage member was rejected: ${f.path} ${f.detail}")
    }
  }

  // spec: wire-codec — Scenario: A successful response whose body does not fit
  test("a successful response whose body does not fit") {
    val body  = """{"model":"m","answers":{"a":{"type":"noul","noul":"high"}}}"""
    val qs    = QuestionSet("a" -> Noul(Entry.text("n?")))
    val trace = RequestId.fromHeader("req-8")
    SystemOne.decodeResponse(qs, trace, body) match {
      case Left(f)  =>
        assert(f.path.nonEmpty, "the failure names no path")
        assert(f.path.contains("a"), s"the path does not name where it stopped fitting: ${f.path}")
        assertEquals(f.requestId, trace, "the caller-supplied trace is carried on the failure side")
      case Right(_) => fail("a body that does not fit was accepted")
    }
  }

  // spec: wire-codec — Scenario: An unrecognised member is passed over
  test("an unrecognised member is passed over") {
    val body =
      """{"model":"m","answers":{"a":{"type":"noul","noul":0.5,"futureField":true}},"extra":{"new":1}}"""
    val qs   = QuestionSet("a" -> Noul(Entry.text("n?")))
    SystemOne.decodeResponse(qs, None, body) match {
      case Right(set) => assertEquals(set.answers("a"), NoulAnswer(0.5))
      case Left(f)    => fail(s"an unrecognised member broke the read: ${f.path} ${f.detail}")
    }
  }

  // spec: wire-codec — Scenario: An answer marker that is not recognised
  test("an answer marker that is not recognised") {
    val body = """{"model":"m","answers":{"a":{"type":"fuzzy","noul":0.5}}}"""
    val qs   = QuestionSet("a" -> Noul(Entry.text("n?")))
    SystemOne.decodeResponse(qs, None, body) match {
      case Left(f)  =>
        assert(f.path.nonEmpty, "the failure names no path")
        assert(f.path.contains("a"), s"the path does not name the answer: ${f.path}")
      case Right(_) => fail("an unrecognised marker produced a partial answer set")
    }
  }

  // spec: wire-codec — legend and probabilities are keyed by level number over
  // the DECLARED levels: a key outside the declared range, or in a
  // non-canonical form, does not fit and is rejected where it broke (Ring-8
  // D2 — symmetric with the choice option-membership check)
  test("a level key outside the declared range or non-canonical is rejected") {
    val qs = QuestionSet("s" -> Score[3](Entry.text("s?"), Vector(Entry.text("a"), Entry.text("b"), Entry.text("c"))))
    for (key <- List("3", "-1", "+1", "01")) {
      val body =
        s"""{"model":"m","answers":{"s":{"type":"score","score":0.5,"legend":{"$key":"x"},"probabilities":{"0":1.0},"confidence":0.5}}}"""
      SystemOne.decodeResponse(qs, None, body) match {
        case Left(f)  => assert(f.path.contains("s"), s"key '$key': the path does not name where it broke: ${f.path}")
        case Right(_) => fail(s"level key '$key' on a 3-level score was silently absorbed")
      }
    }
  }

  // spec: wire-codec — Property: Reading is total over well-formed responses
  // genResponseCase — a well-formed response built FROM the question set plus a
  // perturbation flag (~half of runs), so both branches are reached by
  // construction. Every perturbation is guaranteed non-fitting: Right must
  // carry one answer per asked name; Left must name a path.
  private val genResponseCase: Gen[(QuestionSet, Json, Int, Boolean)] =
    for {
      questions <- genQuestionSet
      response  <- genWellFormedResponse(questions)
      seed      <- Gen.choose(0, 1000)
      perturbed <- Gen.oneOf(true, false)
    } yield (questions, response, seed, perturbed)

  property("reading-is-total-over-well-formed-responses") {
    forAll(genResponseCase) { case (questions, response, seed, perturbed) =>
      val body = Json.render(if (perturbed) perturb(response, questions, seed) else response)
      classify(true, if (perturbed) s"perturbed-${seed % 4}" else "fitting") {
        SystemOne.decodeResponse(questions, None, body) match {
          case Right(answers) => !perturbed && answers.names == questions.names
          case Left(failure)  => perturbed && failure.path.nonEmpty
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // Recorded fixtures — the documented request and response examples
  // --------------------------------------------------------------------------

  private def fixture(name: String): String = {
    val src = Source.fromResource(name, getClass.getClassLoader)
    try src.mkString
    finally src.close()
  }

  private lazy val fixtureQuestions = QuestionSet(
    "urgency"     -> Noul(Entry.text("Does this message express urgency?")),
    "department"  -> Choice[String](
      Entry.text("Which team should handle this ticket?"),
      ListMap("billing" -> Entry.text("Billing team"), "technical" -> Entry.nothing),
      Some(_)
    ),
    "frustration" -> Score[3](
      Entry.text("How frustrated is the customer?"),
      Vector(Entry.text("Calm"), Entry.text("Concerned"), Entry.text("Angry"))
    )
  )

  // spec: wire-codec — Proof Obligations: recorded request fixtures
  test("the recorded request fixture is reproduced byte for byte") {
    val rendered = Json.render(SystemOne.renderRequest("The customer wants their money back.", "jev-latest", fixtureQuestions))
    assertEquals(rendered, fixture("systemone-request.json").trim)
  }

  // spec: wire-codec — Proof Obligations: recorded response fixtures
  test("the recorded response fixture is read") {
    SystemOne.decodeResponse(fixtureQuestions, None, fixture("systemone-response.json")) match {
      case Right(set) =>
        assertEquals(set.answers("urgency"), NoulAnswer(0.999))
        assertEquals(set.answers("department"), ChoiceAnswer("billing", Map("billing" -> 0.8, "technical" -> 0.2), 0.9))
        assertEquals(
          set.answers("frustration"),
          ScoreAnswer(
            1.35,
            Map(0 -> Entry.text("Calm"), 1 -> Entry.text("Concerned"), 2 -> Entry.text("Angry")),
            Map(0 -> 0.1, 1                -> 0.55, 2                    -> 0.35),
            0.7
          )
        )
        assertEquals(set.model, "jev-latest")
        assertEquals(set.usage, Usage(Some(312), Some(48)))
      case Left(f)    => fail(s"the recorded response was rejected: ${f.path} ${f.detail}")
    }
  }

  // spec: wire-codec — Proof Obligations: unrecognised members change nothing
  // (recorded fixture with an added member)
  test("the recorded response with an added member is read identically") {
    val plain = SystemOne.decodeResponse(fixtureQuestions, None, fixture("systemone-response.json"))
    val added = SystemOne.decodeResponse(fixtureQuestions, None, fixture("systemone-response-unknown-member.json"))
    (plain, added) match {
      case (Right(a), Right(b)) => assertEquals(a, b)
      case other                => fail(s"an added member changed the read: $other")
    }
  }

  // --------------------------------------------------------------------------
  // Contract surface — the checked Entry conversion (D2) and the core givens
  // --------------------------------------------------------------------------

  // contract D2: Entry.fromJson admits the entry shapes and names the path of
  // the first member that is not entry-shaped
  test("fromJson admits entry shapes and rejects non-entry shapes naming the path") {
    assertEquals(Entry.fromJson(Json.JString("s")), Right(Entry.text("s")))
    assertEquals(Entry.fromJson(Json.JNull), Right(Entry.nothing))
    assert(Entry.fromJson(Json.JObject(Vector("a" -> Json.JString("x")))).isRight)
    assert(Entry.fromJson(Json.JArray(Vector(Json.JString("x")))).isRight)

    assertEquals(Entry.fromJson(Json.JNumber(1)).left.toOption, Some("$"), "a top-level number names the root")
    assertEquals(
      Entry.fromJson(Json.JObject(Vector("a" -> Json.JObject(Vector("b" -> Json.JBool(true)))))).left.toOption,
      Some("$.a.b"),
      "the path names the member that is not entry-shaped"
    )
    assertEquals(
      Entry.fromJson(Json.JArray(Vector(Json.JString("ok"), Json.JBool(true)))).left.toOption,
      Some("$.1"),
      "an array path names the index that is not entry-shaped"
    )
  }

  // spec: wire-codec — QuestionSet.fromEntries: the non-empty factory admits a
  // populated set and reports the empty one as absent (contract surface)
  test("QuestionSet.fromEntries admits non-empty and reports empty as absent") {
    val qs = QuestionSet.fromEntries(List("a" -> Noul(Entry.text("x?"))))
    assertEquals(qs.map(_.names), Some(Set("a")))
    assertEquals(qs.map(_.size), Some(1))
    assertEquals(QuestionSet.fromEntries(List.empty), None)
  }

  // spec: wire-codec — a malformed JSON body is rejected where the reader
  // stopped, never accepted silently (Ring 3: the parser's error branches are
  // production logic — every one is exercised here)
  test("malformed JSON is rejected where the reader stopped") {
    val unescapedTab = "\"a\tb\"" // a raw control character inside a string
    val malformed    = List(
      "",
      "{",
      "[",
      "\"",
      """{"a":1""",
      """[1,2""",
      """{"a" 1}""",
      """{"a":1 "b":2}""",
      """[1 2]""",
      """{"a":01}""",
      "tru",
      "nul",
      """"a\q"""",
      """"a""",
      """{"a":1} extra""",
      "?",
      unescapedTab
    )
    malformed.foreach { body =>
      Json.parse(body) match {
        case Left(e)  => assert(e.detail.nonEmpty, s"'$body' was rejected without saying why")
        case Right(v) => fail(s"malformed input parsed as $v: '$body'")
      }
    }
    // the nesting bound is a boundary, not a cliff: at it, accepted; past it, rejected
    assert(Json.parse("[" * 101 + "]" * 101).isRight, "nesting at the bound should still parse")
    assert(Json.parse("[" * 102 + "]" * 102).isLeft, "nesting past the bound should be rejected")
  }

  // spec: wire-codec — Requirement: A response that does not fit is rejected
  // where it broke — exercised across EVERY failure surface, not only the
  // ones the scenarios name (Ring 3: the decoder's error paths are production
  // logic; a failure must name where it broke AND say why)
  test("every non-fitting body is rejected where it broke, saying why") {
    val noulQ              = QuestionSet("a" -> Noul(Entry.text("n?")))
    val twoQs              = QuestionSet("a" -> Noul(Entry.text("n?")), "b" -> Noul(Entry.text("m?")))
    val choiceQ            = QuestionSet("c" -> Choice[String](Entry.text("c?"), ListMap("x" -> Entry.text("X"), "y" -> Entry.text("Y")), Some(_)))
    val restrictiveChoiceQ = QuestionSet(
      "c" -> Choice[String](
        Entry.text("c?"),
        ListMap("x" -> Entry.text("X"), "y" -> Entry.text("Y")),
        k => Option.when(k == "x" || k == "y")(k)
      )
    )
    val scoreQ             = QuestionSet("s" -> Score[3](Entry.text("s?"), Vector(Entry.text("a"), Entry.text("b"), Entry.text("c"))))

    val fitting                                    = """{"type":"noul","noul":0.5}"""
    val cases: List[(QuestionSet, String, String)] = List(
      // a body that is not JSON, or is JSON but not an object, does not fit at the root
      (noulQ, "not json", "$"),
      (noulQ, "[1,2]", "$"),
      (noulQ, "5", "$"),
      (noulQ, """{"model":"m"} trailing""", "$"),
      // top-level members
      (noulQ, """{"answers":{"a":%s}}""".formatted(fitting), "model"),
      (noulQ, """{"model":5,"answers":{"a":%s}}""".formatted(fitting), "model"),
      (noulQ, """{"model":"m"}""", "answers"),
      (noulQ, """{"model":"m","answers":[]}""", "answers"),
      // usage: present members must fit
      (noulQ, s"""{"model":"m","usage":"x","answers":{"a":$fitting}}""", "usage"),
      (noulQ, s"""{"model":"m","usage":{"input_tokens":"x"},"answers":{"a":$fitting}}""", "usage.input_tokens"),
      (noulQ, s"""{"model":"m","usage":{"input_tokens":1e20},"answers":{"a":$fitting}}""", "usage.input_tokens"),
      (noulQ, s"""{"model":"m","usage":{"output_tokens":"x"},"answers":{"a":$fitting}}""", "usage.output_tokens"),
      // a missing answer for an asked name
      (twoQs, s"""{"model":"m","answers":{"a":$fitting}}""", "answers.b"),
      // answer shape and marker
      (noulQ, """{"model":"m","answers":{"a":5}}""", "answers.a"),
      (noulQ, """{"model":"m","answers":{"a":{"noul":0.5}}}""", "answers.a"),    // a missing member is a defect of the parent
      (noulQ, """{"model":"m","answers":{"a":{"type":5}}}""", "answers.a.type"),
      (noulQ, """{"model":"m","answers":{"a":{"type":"noul"}}}""", "answers.a"), // missing member = defect of the parent
      (noulQ, """{"model":"m","answers":{"a":{"type":"noul","noul":"x"}}}""", "answers.a.noul"),
      // a marker that does not fit the asked kind is rejected at the marker
      (choiceQ, s"""{"model":"m","answers":{"c":$fitting}}""", "answers.c.type"),
      (noulQ, """{"model":"m","answers":{"a":{"type":"choice","choice":"x","probabilities":{},"confidence":0.5}}}""", "answers.a.type"),
      (noulQ, """{"model":"m","answers":{"a":{"type":"score","score":0.5,"legend":{},"probabilities":{},"confidence":0.5}}}""", "answers.a.type"),
      // a marker that fits no known kind is rejected at the marker, describing itself
      (noulQ, """{"model":"m","answers":{"a":{"type":"bogus","noul":0.5}}}""", "answers.a.type"),
      // choice answer fit failures
      (choiceQ, """{"model":"m","answers":{"c":{"type":"choice","probabilities":{"x":1.0},"confidence":0.5}}}""", "answers.c"),
      (choiceQ, """{"model":"m","answers":{"c":{"type":"choice","choice":5,"probabilities":{"x":1.0},"confidence":0.5}}}""", "answers.c.choice"),
      (
        restrictiveChoiceQ,
        """{"model":"m","answers":{"c":{"type":"choice","choice":"zzz","probabilities":{"x":1.0},"confidence":0.5}}}""",
        "answers.c.choice"
      ),
      (choiceQ, """{"model":"m","answers":{"c":{"type":"choice","choice":"x","probabilities":5,"confidence":0.5}}}""", "answers.c.probabilities"),
      (
        restrictiveChoiceQ,
        """{"model":"m","answers":{"c":{"type":"choice","choice":"x","probabilities":{"zzz":1.0},"confidence":0.5}}}""",
        "answers.c.probabilities"
      ),
      (
        choiceQ,
        """{"model":"m","answers":{"c":{"type":"choice","choice":"x","probabilities":{"x":"y"},"confidence":0.5}}}""",
        "answers.c.probabilities"
      ),
      (choiceQ, """{"model":"m","answers":{"c":{"type":"choice","choice":"x","probabilities":{"x":1.0}}}}""", "answers.c"),
      (
        choiceQ,
        """{"model":"m","answers":{"c":{"type":"choice","choice":"x","probabilities":{"x":1.0,"y":0.0},"confidence":"high"}}}""",
        "answers.c.confidence"
      ),
      // score answer fit failures
      (scoreQ, """{"model":"m","answers":{"s":{"type":"score","legend":{"0":"a"},"probabilities":{"0":1.0},"confidence":0.5}}}""", "answers.s"),
      (scoreQ, """{"model":"m","answers":{"s":{"type":"score","score":0.5,"probabilities":{"0":1.0},"confidence":0.5}}}""", "answers.s"),
      (
        scoreQ,
        """{"model":"m","answers":{"s":{"type":"score","score":0.5,"legend":5,"probabilities":{"0":1.0},"confidence":0.5}}}""",
        "answers.s.legend"
      ),
      (
        scoreQ,
        """{"model":"m","answers":{"s":{"type":"score","score":0.5,"legend":{"0":true},"probabilities":{"0":1.0},"confidence":0.5}}}""",
        "answers.s.legend.0"
      ),
      (scoreQ, """{"model":"m","answers":{"s":{"type":"score","score":0.5,"legend":{"0":"a","1":"b","2":"c"},"confidence":0.5}}}""", "answers.s"),
      (
        scoreQ,
        """{"model":"m","answers":{"s":{"type":"score","score":0.5,"legend":{"0":"a","1":"b","2":"c"},"probabilities":{"9":1.0},"confidence":0.5}}}""",
        "answers.s.probabilities.9"
      ),
      (
        scoreQ,
        """{"model":"m","answers":{"s":{"type":"score","score":0.5,"legend":{"0":"a","1":"b","2":"c"},"probabilities":{"0":"x"},"confidence":0.5}}}""",
        "answers.s.probabilities.0"
      ),
      (
        scoreQ,
        """{"model":"m","answers":{"s":{"type":"score","score":0.5,"legend":{"0":"a","1":"b","2":"c"},"probabilities":{"0":1.0,"1":0.0,"2":0.0}}}}""",
        "answers.s"
      ),
      (
        scoreQ,
        """{"model":"m","answers":{"s":{"type":"score","score":0.5,"legend":{"0":"a","1":"b","2":"c"},"probabilities":5,"confidence":0.9}}}""",
        "answers.s.probabilities"
      ),
      (
        scoreQ,
        """{"model":"m","answers":{"s":{"type":"score","score":0.5,"legend":{"0":"a","1":"b","2":"c"},"probabilities":{"0":0.5,"1":0.3,"2":0.2},"confidence":"x"}}}""",
        "answers.s.confidence"
      ),
      // the first key past the declared range is rejected at the key itself
      (
        scoreQ,
        """{"model":"m","answers":{"s":{"type":"score","score":0.5,"legend":{"0":"a","1":"b","2":"c"},"probabilities":{"0":0.5,"1":0.3,"2":0.2,"3":0.0},"confidence":0.9}}}""",
        "answers.s.probabilities.3"
      )
    )
    cases.foreach { case (qs, body, fragment) => expectInvalid(qs, body, fragment) }
  }

  private def expectInvalid(questions: QuestionSet, body: String, pathFragment: String): Unit =
    SystemOne.decodeResponse(questions, None, body) match {
      case Left(f)  =>
        assert(f.path.nonEmpty, s"$body — the failure names no path")
        assert(f.path.contains(pathFragment), s"$body — the path '${f.path}' does not name '$pathFragment'")
        assert(f.path == "$" || !f.path.contains("$"), s"$body — the path '${f.path}' retains an unstripped entry prefix")
        assert(f.detail.nonEmpty, s"$body — the failure says nothing about what broke")
      case Right(_) => fail(s"a body that does not fit was accepted: $body")
    }

  // spec: wire-codec — Concepts Introduced: StateEncoder — the core givens
  test("the core StateEncoder givens produce entry shapes") {
    assertEquals(summon[StateEncoder[String]].encode("hello"), Entry.text("hello"))
    assertEquals(
      summon[StateEncoder[Map[String, String]]].encode(Map("a" -> "b")),
      Entry.obj("a" -> Entry.text("b"))
    )
    assertEquals(
      summon[StateEncoder[Seq[String]]].encode(Seq("a", "b")),
      Entry.arr(Entry.text("a"), Entry.text("b"))
    )
    assertEquals(summon[StateEncoder[Option[String]]].encode(None), Entry.nothing)
    assertEquals(summon[StateEncoder[Option[String]]].encode(Some("x")), Entry.text("x"))
    assertEquals(
      summon[StateEncoder[(String, String)]].encode(("a", "b")),
      Entry.arr(Entry.text("a"), Entry.text("b"))
    )
    assertEquals(summon[StateEncoder[Entry]].encode(Entry.text("e")), Entry.text("e"))
  }
}
