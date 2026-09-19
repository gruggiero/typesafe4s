package typesafe4s.integration

import scala.annotation.unused
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

import kyo.compat.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.{classify, forAll}

import typesafe4s.{ApiKey, Entry, HttpResponse, Noul, NoulAnswer, RequestId, RetryPolicy, TypesafeClient, TypesafeException, Usage}
import typesafe4s.TypesafeException.*
import typesafe4s.client.{LogLevel, TypesafeConfig}
import typesafe4s.client.internal.{ManualClock, StandInTransport}
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// TEST ORACLE (facade half) — spec: effect-portability (change:
// add-typesafe4s-sdk, spec 6/8)
//
// Written from the spec and the Gate-1 contract BEFORE any implementation.
//
// This suite names the row's OWN facade — `typesafe4s.TypesafeClient` — so
// it lives in `published-shared`: the compile-only baseline row has no
// facade and does not compile it. Every PUBLISHED artifact compiles and
// runs it, so the same property exercises each ecosystem's own conversions.
// ============================================================================
final class LoweredClientParitySuite extends ScalaCheckSuite {

  @unused private given ExecutionContext = munitExecutionContext

  private val rid: Option[RequestId] = RequestId.fromHeader("oracle-rid")

  // --------------------------------------------------------------------------
  // spec: effect-portability — generator strategy `genOutcome` (constructive):
  // a success carrying a value from a small alphabet, or a failure drawn
  // from the ENUMERATED members of the SDK's failure family — enumerated so
  // every member crosses the conversion on a short run.
  // --------------------------------------------------------------------------

  private val familyMembers: List[TypesafeException] = List(
    BadRequest(rid, "malformed"),
    Authentication(rid, "unauthenticated"),
    PermissionDenied(rid, "forbidden"),
    NotFound(rid, "unknown"),
    UnprocessableEntity(rid, "unprocessable"),
    RateLimit(rid, Some(3.seconds), "too many"),
    Overloaded(rid, "overloaded"),
    InternalServer(503, rid, "service fault"),
    ResponseValidation(rid, "answers.q", "wrong shape"),
    ConnectionFailed(new java.io.IOException("refused")),
    Timeout(5.seconds),
    MissingAnswer("q1"),
    InvalidQuestion(Some("q1"), "over the limit"),
    MissingCredential("TYPESAFE_API_KEY"),
    InvalidConfiguration("TYPESAFE_LOG_LEVEL", "not a level")
  )

  private val genOutcome: Gen[Try[Int]] = Gen.frequency(
    1 -> Gen.choose(-500, 500).map(n => Success(n)),
    1 -> Gen.oneOf(familyMembers).map(e => Failure(e))
  )

  private def labelOf(outcome: Try[Int]): String = outcome match {
    case Success(_) => "success"
    case Failure(e) => e.getClass.getSimpleName
  }

  private def run[A](c: CIO[A]): Try[A] = Try(Await.result(c.unsafeRun, 30.seconds))

  // spec: effect-portability — Property: Lowering then lifting preserves
  // the result. `lower`/`lift` are THIS row's own conversions — the
  // property passes through the facade exactly as a caller's operation
  // would. `classify` labels: success or which failure member.
  property("lowering-then-lifting-preserves-the-result") {
    forAll(genOutcome) { outcome =>
      classify(outcome.isFailure, s"member: ${labelOf(outcome)}", "success") {
        val carried = CIO.get(outcome)
        run(TypesafeClient.lift(TypesafeClient.lower(carried))) == run(carried)
      }
    }
  }

  // --------------------------------------------------------------------------
  // facade-driven scenarios — the full path a caller takes: `of` → op → F
  // --------------------------------------------------------------------------

  private val config = TypesafeConfig(
    apiKey = ApiKey.of("test-key"),
    baseUrl = "https://unit.test",
    model = "test-model",
    allowance = 30.seconds,
    retryPolicy = RetryPolicy.of(attempts = 0, totalBound = None),
    headers = Nil,
    logLevel = LogLevel.Error
  )

  private val okResponse = HttpResponse(
    200,
    List("x-typesafe-request-id" -> "req-1"),
    """{"model":"answer-model","answers":{"q":{"type":"noul","noul":0.9}},"usage":{"input_tokens":10,"output_tokens":5}}"""
  )

  private val noulQ = Noul(Entry.text("is it true?"))

  // spec: effect-portability — Requirement: Behaviour is shown to agree on
  // every artifact. One ask through the row's facade produces the same
  // Evaluation on every published artifact — the answers, the answering
  // model, the cost, and the trace.
  test("an-ask-produces-the-same-evaluation-on-every-artifact") {
    val program =
      for {
        clock     <- ManualClock.init
        transport <- StandInTransport.init(List(Step.Answer(okResponse)), Step.Answer(okResponse))
        client     = TypesafeClient.of(config, transport)
        eval      <- TypesafeClient.lift(client.systemOneDynamic("subject")(Seq("q" -> noulQ)))
      } yield {
        assertEquals(eval.model, "answer-model")
        assertEquals(eval.usage, Usage(Some(10L), Some(5L)))
        assertEquals(eval.requestId, RequestId.fromHeader("req-1"))
        assertEquals(eval.answers.noul("q"), Right(NoulAnswer(0.9)))
      }
    program.unsafeRun
  }

  // spec: effect-portability — Scenario: Ordinary failures elsewhere. On
  // every ecosystem except the one with a typed channel, a failure arrives
  // through the ordinary failure channel — and on ALL of them it is a
  // member of the closed family.
  test("ordinary-failures-elsewhere") {
    val member  = Overloaded(rid, "overloaded")
    val program =
      for {
        clock     <- ManualClock.init
        transport <- StandInTransport.init(Nil, Step.Fail(member))
        client     = TypesafeClient.of(config, transport)
        outcome   <- TypesafeClient.lift(client.systemOneDynamic("subject")(Seq("q" -> noulQ))).liftToTry
      } yield assertEquals(outcome, Failure(member))
    program.unsafeRun
  }
}
