package typesafe4s.integration

import scala.annotation.unused
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

import kyo.compat.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.{classify, forAll}

// ============================================================================
// TEST ORACLE (parity half) — spec: effect-portability (change:
// add-typesafe4s-sdk, spec 6/8)
//
// Written from the spec and the Gate-1 contract BEFORE any implementation.
//
// This suite is written ONCE, against `CIO`, and is recompiled and executed
// for EVERY backend row — a program's observable result must agree with an
// independently computed model regardless of which artifact runs it.
//
// GREEN-BY-DESIGN: the property exercises the carrier itself (kyo.compat),
// which no spec-6 production code sits under — it passes on the contract
// already and is recorded as such in the oracle polarity run. What it still
// catches: a binding whose composition semantics drifted from the model
// (the kyo row's fused `defer` did, once — hence the property exists).
// ============================================================================
final class EffectPortabilityParitySuite extends ScalaCheckSuite {

  // the ox binding's `unsafeRun` takes an ExecutionContext; the others do
  // not. A shared suite supplies one and marks it unused so the rows that
  // ignore it still compile under -Werror -Wunused.
  @unused private given ExecutionContext = munitExecutionContext

  // --------------------------------------------------------------------------
  // spec: effect-portability — generator strategy `genProgram` (constructive):
  // a program is a START (a lifted value or a failure) followed by up to a
  // bounded depth of steps drawn from the enumerated carrier operations —
  // lift a value (the start), map, chain, fail, recover.
  // --------------------------------------------------------------------------

  final private class Boom(tag: Int) extends RuntimeException(s"boom-$tag")

  private enum Step {
    case MapStep(delta: Int)        // map(_ + delta)
    case ChainStep(delta: Int)      // flatMap(n => CIO.value(n + delta))
    case FailStep(boom: Boom)       // a failure, if the program reaches it
    case RecoverStep(fallback: Int) // recover(_ => CIO.value(fallback))
  }

  final private case class Program(start: Either[Boom, Int], steps: List[Step])

  private def interpret(program: Program): CIO[Int] =
    program.steps.foldLeft(program.start.fold(b => CIO.fail(b), n => CIO.value(n)): CIO[Int]) { (c, step) =>
      step match {
        case Step.MapStep(d)     => c.map(_ + d)
        case Step.ChainStep(d)   => c.flatMap(n => CIO.value(n + d))
        case Step.FailStep(b)    => c.flatMap(_ => CIO.fail(b))
        case Step.RecoverStep(f) => c.recover(_ => CIO.value(f))
      }
    }

  // the INDEPENDENT model: the same program evaluated on scala.util.Try —
  // the expected result is computed from the drawn structure, never from
  // running the carrier, so the property compares against a model and not
  // against itself.
  private def expectedOf(program: Program): Try[Int] =
    program.steps.foldLeft(program.start.fold(Failure(_), Success(_)): Try[Int]) { (t, step) =>
      step match {
        case Step.MapStep(d)     => t.map(_ + d)
        case Step.ChainStep(d)   => t.flatMap(n => Success(n + d))
        case Step.FailStep(b)    => t.flatMap(_ => Failure(b))
        case Step.RecoverStep(f) => t.recover { case _ => f }
      }
    }

  private val genBoom: Gen[Boom] = Gen.choose(0, 9).map(new Boom(_))

  private val genStep: Gen[Step] = Gen.frequency(
    3 -> Gen.choose(-20, 20).map(Step.MapStep(_)),
    2 -> Gen.choose(-20, 20).map(Step.ChainStep(_)),
    2 -> genBoom.map(Step.FailStep(_)),
    2 -> Gen.choose(-100, 100).map(Step.RecoverStep(_))
  )

  private val genProgram: Gen[Program] =
    for {
      start <- Gen.frequency(
                 4 -> Gen.choose(-100, 100).map(n => Right(n): Either[Boom, Int]),
                 1 -> genBoom.map(b => Left(b): Either[Boom, Int])
               )
      depth <- Gen.choose(0, 5)
      steps <- Gen.listOfN(depth, genStep)
    } yield Program(start, steps)

  private def run[A](c: CIO[A]): Try[A] = Try(Await.result(c.unsafeRun, 30.seconds))

  // spec: effect-portability — Property: The carrier composes the same way
  // on every artifact. `classify` labels: program depth, whether it fails.
  property("carrier-composes-the-same-way") {
    forAll(genProgram) { program =>
      val expected = expectedOf(program)
      classify(program.steps.isEmpty, "depth 0", s"depth ${program.steps.size}") {
        classify(expected.isFailure, "fails", "succeeds") {
          run(interpret(program)) == expected
        }
      }
    }
  }
}
