package typesafe4s

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.{classify, forAll}

import typesafe4s.json.Json

// ============================================================================
// TEST ORACLE — spec: question-batching (change: add-typesafe4s-sdk, 8/8)
//
// Written from the spec and the Gate-1 contract BEFORE any implementation.
// Division and estimation are pure, so the oracle lives in core.
//
// Generators are constructive — ScalaCheck's classify is informational
// only (no coverage assertion exists in this project), so the interesting
// cases are reached BY CONSTRUCTION, never by rare draws. Sizes are drawn
// in rendered-char space and calibrated against the estimator itself:
// `estimate` is used to SIZE inputs (input construction may consult the
// function under test); every ASSERTION is on the spec's invariants —
// partition completeness, per-batch fit, named refusal — never on the
// estimator's internals.
// ============================================================================
final class QuestionBatchingProperties extends ScalaCheckSuite {

  // --------------------------------------------------------------------------
  // Building blocks — questions are Noul(text(n)) so rendered size is
  // controllable; the state is a String (StateEncoder given exists).
  // --------------------------------------------------------------------------

  private def question(name: String, instructionChars: Int): (String, Question[?]) =
    name -> Noul(Entry.text("x" * math.max(0, instructionChars)))

  private def est(state: String, qs: QuestionSet): Int =
    TokenBudget.estimate(state, qs).approximateTokens

  // estimate of ONE question alongside the state — a question's marginal
  // cost measured through the function under test, so sizing tracks any
  // estimator shape that is monotone in size.
  private def marginalOf(state: String, q: (String, Question[?])): Int =
    est(state, QuestionSet(q)) - est(state, QuestionSet("probe" -> Noul(Entry.text(""))))

  // construct a question whose marginal estimate lands near `target`
  // tokens — binary search on instruction length; no formula knowledge.
  private def sizedToFill(state: String, name: String, targetTokens: Int): (String, Question[?]) = {
    def measure(chars: Int): Int      = marginalOf(state, question(name, chars))
    @scala.annotation.tailrec
    def search(lo: Int, hi: Int): Int =
      if (hi - lo <= 1) lo
      else {
        val mid = lo + (hi - lo) / 2
        if (measure(mid) <= targetTokens) search(mid, hi) else search(lo, mid)
      }
    question(name, search(0, 300_000))
  }

  // --------------------------------------------------------------------------
  // Generator strategy `genOversizedSet` (spec: Properties) — constructive.
  // Draws a state size and 1–200 questions with sizes so the total USUALLY
  // exceeds the budget; boundary cases are reached BY CONSTRUCTION via a
  // case tag — nothing is filtered:
  //   "fits-exactly"  — the set's estimate lands on/just under the limit
  //   "one-over"      — the estimate lands just over the limit
  //   "each-alone"    — every question fills a batch by itself
  //   "random"        — arbitrary sizes, usually oversized
  // Labels are INTENT; assertions read the actual estimate — a mis-sized
  // draw still must satisfy the invariant it lands in.
  // --------------------------------------------------------------------------

  private val smallQuestion: Gen[(String, Question[?])] =
    Gen.zip(Gen.identifier, Gen.choose(4, 400)).map { case (n, l) => question(n, l) }

  private def setOf(qs: List[(String, Question[?])]): QuestionSet =
    QuestionSet(qs.head, qs.tail*)

  private val genState: Gen[String] = Gen.choose(0, 4000).map("s" * _)

  // a set whose total estimate is meant to land at `limit + slack`
  private def genSizedSet(targetOver: Int): Gen[(String, QuestionSet)] =
    for {
      state  <- genState
      n      <- Gen.choose(1, 6)
      filler <- Gen.listOfN(n - 1, Gen.choose(4, 200).map(l => question(s"filler$l", l)))
    } yield {
      val partial = QuestionSet(question("first", 8), filler*)
      val room    = TokenBudget.limit.approximateTokens - est(state, partial) - targetOver
      val closer  = sizedToFill(state, "closer", room)
      (state, setOf((question("first", 8) :: filler) :+ closer))
    }

  private val genFitsExactly: Gen[(String, QuestionSet, String)] =
    genSizedSet(targetOver = 0).map { case (s, q) => (s, q, "fits-exactly") }

  private val genOneUnitOver: Gen[(String, QuestionSet, String)] =
    genSizedSet(targetOver = -1).map { case (s, q) => (s, q, "one-over") }

  private val genEachAlone: Gen[(String, QuestionSet, String)] =
    for {
      state <- Gen.choose(0, 500).map("s" * _)
      n     <- Gen.choose(2, 5)
    } yield {
      // each question sized to ~70% of the room beside the state: alone it
      // fits, any two together overflow — every question its own batch.
      val solo = QuestionSet(question("probe", 4))
      val room = TokenBudget.limit.approximateTokens - est(state, solo)
      val qs   = (1 to n).toList.map(i => sizedToFill(state, s"q$i", (room * 7) / 10))
      (state, setOf(qs), "each-alone")
    }

  private val genRandom: Gen[(String, QuestionSet, String)] =
    for {
      state <- genState
      n     <- Gen.choose(1, 200)
      qs    <- Gen.listOfN(n, Gen.choose(4, 8000)).map(ls => ls.zipWithIndex.map { case (l, i) => question(s"q$i", l) })
    } yield (state, setOf(qs), "random")

  private val genOversizedSet: Gen[(String, QuestionSet, String)] =
    Gen.frequency(
      3 -> genFitsExactly,
      3 -> genOneUnitOver,
      2 -> genEachAlone,
      8 -> genRandom
    )

  // --------------------------------------------------------------------------
  // spec: question-batching — Property: Dividing loses nothing and
  // duplicates nothing. The batches' questions, taken together, are exactly
  // the question set.
  // --------------------------------------------------------------------------
  property("dividing-loses-nothing") {
    forAll(genOversizedSet) { case (state, questions, intent) =>
      val split = TokenBudget.split(state, questions)
      val whole = split.exists { batches =>
        val seen = batches.flatMap(_.questions.names)
        seen.sorted == questions.names.toList.sorted && seen.distinct.size == questions.size
      }
      classify(split.exists(_.size == 1), "one-batch") {
        classify(true, intent)(whole)
      }
    }
  }

  // --------------------------------------------------------------------------
  // spec: question-batching — Property: Every batch fits alongside the
  // state. A near-budget question arises by construction (each-alone draws
  // questions at ~70% of the room).
  // --------------------------------------------------------------------------
  property("every-batch-fits") {
    forAll(genOversizedSet) { case (state, questions, _) =>
      TokenBudget.split(state, questions) match {
        case Right(batches) =>
          batches.forall(b => TokenBudget.estimate(state, b.questions).within(TokenBudget.limit))
        case Left(_)        => false
      }
    }
  }

  // --------------------------------------------------------------------------
  // spec: question-batching — Property: A question too large alone is
  // refused, not divided. genUnfittableQuestion — constructive: the offender
  // is sized past the remaining budget by a positive margin and embedded at
  // a drawn position, so refusal is reached from first, middle and last.
  // The no-exchange half of the invariant lives in the client suite (the
  // stand-in counts exchanges).
  // --------------------------------------------------------------------------
  private val genUnfittableQuestion: Gen[(String, QuestionSet, String)] =
    for {
      state   <- genState
      nBefore <- Gen.choose(0, 3)
      nAfter  <- Gen.choose(0, 3)
      before  <- Gen.listOfN(nBefore, smallQuestion)
      after   <- Gen.listOfN(nAfter, smallQuestion)
      // the margin must clear the probe-baseline delta (~2 units between
      // the 4-char probe used for `room` and the empty baseline inside
      // marginalOf), or the "offender" can legitimately fit
      margin  <- Gen.choose(8, 500)
    } yield {
      val room     = TokenBudget.limit.approximateTokens - est(state, QuestionSet(question("probe", 4)))
      val offender = sizedToFill(state, "the-offender", room + margin)
      (state, setOf(before ++ List(offender) ++ after), "the-offender")
    }

  property("unfittable-question-is-refused-not-divided") {
    forAll(genUnfittableQuestion) { case (state, questions, offender) =>
      val pos     = questions.entries.keys.toList.indexOf(offender)
      val refused = TokenBudget.split(state, questions) match {
        case Left(failure) => failure.name.contains(offender)
        case Right(_)      => false
      }
      classify(pos == 0, "offender-first") {
        classify(pos == questions.size - 1, "offender-last")(refused)
      }
    }
  }

  // --------------------------------------------------------------------------
  // spec: question-batching — Scenario: The estimate does not understate.
  // Compared against a service-accounting MODEL the test owns: the rendered
  // request's chars at the standard ~4-chars/token heuristic. The estimator
  // must land at or above that floor — an understating implementation fails.
  // MUST-CONFIRM (task 12.3): the real margin is checked against a live key;
  // this floor is the stand-in.
  // --------------------------------------------------------------------------
  property("estimate-does-not-understate") {
    forAll(genRandom.map { case (s, q, _) => (s, q) }) { case (state, questions) =>
      val renderedChars = Json.render(SystemOne.renderRequest(state, "m-test", questions)).length
      val floor         = math.ceil(renderedChars / 4.0).toInt
      est(state, questions) >= floor
    }
  }

  // spec: question-batching — non-ASCII must not slip under the floor: a
  // BMP non-ASCII char tokenizes at roughly one token each, so n three-byte
  // chars must be charged at least n units — a char-based estimate would
  // charge ~n/3 and fail this check.
  property("estimate-counts-non-ascii-conservatively") {
    forAll(Gen.choose(4, 300)) { n =>
      val qs = QuestionSet("cjk" -> Noul(Entry.text("京" * n)))
      est("s" * 16, qs) >= n
    }
  }

  // --------------------------------------------------------------------------
  // spec: question-batching — Scenario: Estimating an exchange. The estimate
  // covers the state and the questions together — each side contributes:
  // a bigger state raises the estimate; another question raises it too.
  // --------------------------------------------------------------------------
  test("estimating-an-exchange") {
    val small = QuestionSet(question("q1", 64))
    val two   = QuestionSet(question("q1", 64), question("q2", 64))
    assert(est("s" * 64, two) > est("s" * 64, small), "the questions' size was not counted")
    assert(est("s" * 4096, small) > est("s" * 64, small), "the state's size was not counted")
  }

  // spec: question-batching — fit reports the SAME estimate as `estimate`
  // and a within verdict consistent with it (one judgement, not two paths)
  property("fit-reports-the-same-estimate") {
    forAll(genRandom.map { case (s, q, _) => (s, q) }) { case (state, questions) =>
      val fit = TokenBudget.fit(state, questions)
      fit.estimate == TokenBudget.estimate(state, questions) &&
        fit.within == fit.estimate.within(TokenBudget.limit)
    }
  }

  // spec: question-batching — a fitting set divides into exactly one batch
  // carrying the whole set (boundary, exact construction by search)
  test("a-fitting-set-divides-into-one-batch") {
    val state = "s" * 100
    val qs    = setOf(List(question("q1", 16), question("q2", 16), question("q3", 16)))
    TokenBudget.split(state, qs) match {
      case Right(single :: Nil) => assertEquals(single.questions.names, qs.names)
      case other                => fail(s"a small set did not divide into exactly one batch: $other")
    }
  }

  // spec: question-batching — boundary exactness at the limit: find the
  // largest instruction length that still fits beside the state; at that
  // length the set is one batch, and one token more divides or refuses.
  test("the-limit-is-exact") {
    val state                           = "s" * 200
    // largest fitting instruction length by binary search on the estimate
    @scala.annotation.tailrec
    def boundary(lo: Int, hi: Int): Int =
      if (hi - lo <= 1) lo
      else {
        val mid = lo + (hi - lo) / 2
        val qs  = QuestionSet(question("q", mid))
        if (est(state, qs) <= TokenBudget.limit.approximateTokens) boundary(mid, hi) else boundary(lo, mid)
      }
    val len                             = boundary(0, 300_000)
    val fitting                         = QuestionSet(question("q", len))
    assert(TokenBudget.estimate(state, fitting).within(TokenBudget.limit), "boundary set did not fit")
    TokenBudget.split(state, fitting) match {
      case Right(batches) => assertEquals(batches.size, 1, "a boundary-fitting set was divided")
      case Left(e)        => fail(s"a boundary-fitting set was refused: ${e.detail}")
    }
    val over                            = QuestionSet(question("q", len + 4096))
    assert(!TokenBudget.estimate(state, over).within(TokenBudget.limit), "an over-limit set reported fit")
  }
}
