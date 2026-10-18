package typesafe4s

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

// The confidence helpers on NoulAnswer, ChoiceAnswer and ScoreAnswer: pure
// readings of the decoded values, with thresholds restricted to [0, 1].
class AnswerHelpersProperties extends ScalaCheckSuite {

  private val genProbability: Gen[Double] = Gen.frequency(4 -> Gen.choose(0.0, 1.0), 1 -> Gen.oneOf(0.0, 0.5, 1.0))
  private val genOutOfRange: Gen[Double]  = Gen.oneOf(Gen.choose(-10.0, -1e-9), Gen.choose(1.0 + 1e-9, 10.0), Gen.const(Double.NaN))

  private def refuses(f: => Any): Unit = intercept[IllegalArgumentException](f): Unit

  // --------------------------------------------------------------------------
  // NoulAnswer
  // --------------------------------------------------------------------------

  property("NoulAnswer.isTrue holds exactly when noul >= threshold") {
    forAll(genProbability, genProbability) { (noul, t) =>
      assertEquals(NoulAnswer(noul).isTrue(t), noul >= t)
    }
  }

  property("NoulAnswer.isFalse holds exactly when 1 - noul >= threshold") {
    forAll(genProbability, genProbability) { (noul, t) =>
      assertEquals(NoulAnswer(noul).isFalse(t), 1.0 - noul >= t)
    }
  }

  test("NoulAnswer thresholds are inclusive at the boundaries") {
    assert(NoulAnswer(0.8).isTrue(0.8))
    assert(NoulAnswer(0.0).isTrue(0.0))
    assert(!NoulAnswer(0.99).isTrue(1.0))
    assert(NoulAnswer(1.0).isTrue(1.0))
    assert(NoulAnswer(0.0).isFalse(1.0))
  }

  property("NoulAnswer refuses a threshold outside [0, 1]") {
    forAll(genOutOfRange) { t =>
      refuses(NoulAnswer(0.5).isTrue(t))
      refuses(NoulAnswer(0.5).isFalse(t))
    }
  }

  // --------------------------------------------------------------------------
  // ChoiceAnswer
  // --------------------------------------------------------------------------

  private val genProbabilities: Gen[Map[String, Double]] =
    Gen.choose(0, 8).flatMap(n => Gen.listOfN(n, genProbability)).map(ps => ps.zipWithIndex.map((p, i) => s"o$i" -> p).toMap)

  property("ChoiceAnswer.ranked holds every option once, most likely first") {
    forAll(genProbabilities) { ps =>
      val ranked = ChoiceAnswer("o0", ps, 0.5).ranked
      assertEquals(ranked.toMap, ps)
      assertEquals(ranked.size, ps.size)
      assertEquals(ranked.map(_._2), ps.values.toList.sorted.reverse)
    }
  }

  test("ChoiceAnswer.ranked keeps tied options together after the leader") {
    val ranked = ChoiceAnswer("a", Map("a" -> 0.5, "b" -> 0.25, "c" -> 0.25), 0.5).ranked
    assertEquals(ranked.head, "a" -> 0.5)
    assertEquals(ranked.tail.toSet, Set("b" -> 0.25, "c" -> 0.25))
  }

  test("ChoiceAnswer.ranked of no probabilities is empty") {
    assertEquals(ChoiceAnswer("a", Map.empty[String, Double], 0.5).ranked, Nil)
  }

  property("ChoiceAnswer.confidentAt yields the choice exactly when confidence >= threshold") {
    forAll(genProbability, genProbability) { (confidence, t) =>
      assertEquals(ChoiceAnswer("refund", Map("refund" -> 1.0), confidence).confidentAt(t), Option.when(confidence >= t)("refund"))
    }
  }

  property("ChoiceAnswer.confidentAt refuses a threshold outside [0, 1]") {
    forAll(genOutOfRange)(t => refuses(ChoiceAnswer("a", Map("a" -> 1.0), 1.0).confidentAt(t)))
  }

  // --------------------------------------------------------------------------
  // ScoreAnswer
  // --------------------------------------------------------------------------

  private val legend: Map[Int, Entry] = Map(0 -> Entry.text("low"), 1 -> Entry.text("medium"), 2 -> Entry.text("high"))

  private def score(probabilities: Map[Int, Double], confidence: Double = 0.9): ScoreAnswer[3] =
    ScoreAnswer[3](1.0, legend, probabilities, confidence)

  test("ScoreAnswer.level is the most likely level and label its legend entry") {
    val a = score(Map(0 -> 0.1, 1 -> 0.2, 2 -> 0.7))
    assertEquals(a.level, Some(2))
    assertEquals(a.label, Some(Entry.text("high")))
  }

  test("ScoreAnswer.level breaks a tie towards the lowest level") {
    assertEquals(score(Map(2 -> 0.4, 1 -> 0.4, 0 -> 0.2)).level, Some(1))
    assertEquals(score(Map(0 -> 0.5, 2 -> 0.5)).level, Some(0))
  }

  property("ScoreAnswer.level is a level of maximal probability, never beaten by a lower level") {
    forAll(Gen.choose(1, 6).flatMap(n => Gen.listOfN(n, genProbability))) { ps =>
      val probabilities = ps.zipWithIndex.map((p, i) => i -> p).toMap
      val level         = score(probabilities).level.get
      assertEquals(probabilities(level), ps.max)
      assert(probabilities.forall((l, p) => l >= level || p < ps.max))
    }
  }

  test("ScoreAnswer.level and label are None without probabilities, label is None without a legend entry") {
    assertEquals(score(Map.empty).level, None)
    assertEquals(score(Map.empty).label, None)
    assertEquals(score(Map(5 -> 1.0)).label, None)
  }

  test("ScoreAnswer.confidentAt gates the level on the reported confidence") {
    val a = score(Map(0 -> 0.2, 1 -> 0.8), confidence = 0.75)
    assertEquals(a.confidentAt(0.75), Some(1))
    assertEquals(a.confidentAt(0.76), None)
    assertEquals(score(Map.empty, confidence = 1.0).confidentAt(0.0), None)
  }

  property("ScoreAnswer.confidentAt refuses a threshold outside [0, 1]") {
    forAll(genOutOfRange)(t => refuses(score(Map(0 -> 1.0)).confidentAt(t)))
  }
}
