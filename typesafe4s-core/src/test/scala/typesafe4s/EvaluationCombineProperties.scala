package typesafe4s

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

// Usage.+ and Evaluation.combine: merging the per-batch evaluations of one
// batched call into the evaluation of the whole question set.
class EvaluationCombineProperties extends ScalaCheckSuite {

  private val genCount: Gen[Option[Long]] = Gen.option(Gen.choose(0L, 1_000_000L))
  private val genUsage: Gen[Usage]        = for {
    i <- genCount
    o <- genCount
  } yield Usage(i, o)

  private def sum(cs: List[Option[Long]]): Option[Long] = Option.when(cs.exists(_.isDefined))(cs.flatten.sum)

  // --------------------------------------------------------------------------
  // Usage.+
  // --------------------------------------------------------------------------

  property("Usage.+ sums each count, absent only when every part lacks it") {
    forAll(Gen.listOf(genUsage)) { us =>
      val total = us.foldLeft(Usage.empty)(_ + _)
      assertEquals(total, Usage(sum(us.map(_.inputTokens)), sum(us.map(_.outputTokens))))
    }
  }

  property("Usage.empty is the identity of +, and + is commutative") {
    forAll(genUsage, genUsage) { (a, b) =>
      assertEquals(a + Usage.empty, a)
      assertEquals(Usage.empty + a, a)
      assertEquals(a + b, b + a)
    }
  }

  test("Usage.+ counts a part that reports nothing as zero") {
    assertEquals(Usage(Some(10L), None) + Usage(None, None) + Usage(Some(5L), Some(2L)), Usage(Some(15L), Some(2L)))
  }

  // --------------------------------------------------------------------------
  // Evaluation.combine
  // --------------------------------------------------------------------------

  private val rid = RequestId.fromHeader("rid-1")

  private def evaluation(names: List[String], usage: Usage, model: String = "jev-latest"): Evaluation[AnswerSet] = {
    val set = AnswerSet(names.map(n => n -> NoulAnswer(n.length / 10.0)).toMap, model, usage, rid)
    Evaluation(set, model, set.usage, rid)
  }

  // a question set's names split into consecutive, disjoint batches
  private val genBatches: Gen[List[List[String]]] =
    for {
      n     <- Gen.choose(1, 12)
      sizes <- Gen.listOfN(n, Gen.choose(1, 4))
    } yield {
      val names = (0 until sizes.sum).map(i => s"q$i").toList
      sizes.foldLeft((names, List.empty[List[String]])) { case ((rest, acc), k) => (rest.drop(k), acc :+ rest.take(k)) }._2
    }

  property("combining disjoint batches yields every answer once and the summed usage") {
    forAll(genBatches, Gen.listOfN(12, genUsage)) { (batches, usages) =>
      val evals    = batches.zip(usages).map((names, u) => evaluation(names, u))
      val combined = Evaluation.combine(evals)
      val expected = evals.flatMap(_.answers.answers).toMap
      val usage    = evals.map(_.usage).foldLeft(Usage.empty)(_ + _)
      assertEquals(combined.map(_.answers.answers), Right(expected))
      assertEquals(combined.map(_.usage), Right(usage))
      assertEquals(combined.map(_.answers.usage), Right(usage))
      assertEquals(combined.map(_.model), Right("jev-latest"))
    }
  }

  test("a single evaluation combines to itself, request ID included") {
    val e = evaluation(List("a", "b"), Usage(Some(1L), Some(2L)))
    assertEquals(Evaluation.combine(List(e)), Right(e))
  }

  test("several evaluations combine without a single request ID") {
    val combined = Evaluation.combine(List(evaluation(List("a"), Usage.empty), evaluation(List("b"), Usage.empty)))
    assertEquals(combined.map(_.requestId), Right(None))
    assertEquals(combined.map(_.answers.requestId), Right(None))
  }

  test("nothing to combine is refused") {
    Evaluation.combine(Nil) match {
      case Left(e: TypesafeException.InvalidQuestion) => assertEquals(e.name, None)
      case other                                      => fail(s"expected InvalidQuestion, got $other")
    }
  }

  test("a name answered twice is refused, naming it, never overwritten") {
    val evals = List(evaluation(List("a", "dup"), Usage.empty), evaluation(List("b"), Usage.empty), evaluation(List("dup"), Usage.empty))
    Evaluation.combine(evals) match {
      case Left(e: TypesafeException.InvalidQuestion) => assertEquals(e.name, Some("dup"))
      case other                                      => fail(s"expected InvalidQuestion naming 'dup', got $other")
    }
  }

  test("evaluations answered by different models are refused") {
    val evals = List(evaluation(List("a"), Usage.empty, "m1"), evaluation(List("b"), Usage.empty, "m2"))
    Evaluation.combine(evals) match {
      case Left(e: TypesafeException.InvalidQuestion) => assert(e.detail.contains("m1") && e.detail.contains("m2"), e.detail)
      case other                                      => fail(s"expected InvalidQuestion, got $other")
    }
  }
}
