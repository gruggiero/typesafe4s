package typesafe4s.client

import scala.annotation.unused
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.util.Failure

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{ApiKey, Entry, HttpMethod, HttpResponse, Noul, NoulAnswer, RequestId, RetryPolicy, Score, Usage}
import typesafe4s.TypesafeException.{InvalidQuestion, Overloaded}
import typesafe4s.client.internal.{ClientCio, ManualClock, StandInTransport}
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// TEST ORACLE (unit half) — spec: effect-portability (change:
// add-typesafe4s-sdk, spec 6/8)
//
// Written from the spec and the Gate-1 contract BEFORE any implementation.
// Exercises the carrier client (`ClientCio`) and the shared adapter
// (`LoweredClient`) directly — no row facade is named, so this suite
// compiles into EVERY row (including the compile-only baseline) and runs
// on the five published ones via `sbt testUnit`.
// ============================================================================
final class EffectPortabilitySuite extends FunSuite {

  @unused private given ExecutionContext = munitExecutionContext

  private val noRetry = RetryPolicy.of(attempts = 0, totalBound = None)

  private val config = TypesafeConfig(
    apiKey = ApiKey.of("test-key"),
    baseUrl = "https://unit.test",
    model = "test-model",
    allowance = 30.seconds,
    retryPolicy = noRetry,
    headers = List("x-tenant" -> "oracle"),
    logLevel = LogLevel.Error
  )

  private val okResponse = HttpResponse(
    200,
    List("x-typesafe-request-id" -> "req-1"),
    """{"model":"answer-model","answers":{"q":{"type":"noul","noul":0.9}},"usage":{"input_tokens":10,"output_tokens":5}}"""
  )

  private val noulQ = Noul(Entry.text("is it true?"))

  // spec: effect-portability — Scenario: Adding an ecosystem. An ecosystem
  // is adapted by supplying exactly two conversions: a toy effect `() => A`
  // is lowered and lifted, and EVERY shared operation is then available on
  // it — nothing shared needed changing. Both published ops are exercised.
  test("an-ecosystem-is-adapted-by-supplying-two-conversions") {
    type Toy[A] = () => A
    val program =
      for {
        clock     <- ManualClock.init
        transport <- StandInTransport.init(List(Step.Answer(okResponse), Step.Answer(okResponse)), Step.Answer(okResponse))
        carrier    = new ClientCio(config, transport, clock)
        toy        = new LoweredClient[Toy](carrier) {
                       protected def lower[A](c: CIO[A]): Toy[A] = () => Await.result(c.unsafeRun, 30.seconds)
                       protected def lift[A](t: Toy[A]): CIO[A]  = CIO.defer(t())
                     }
        dynamic   <- CIO.defer(toy.systemOneDynamic("subject")(Seq("q" -> noulQ))())
        typed     <- CIO.defer(toy.systemOne("subject")((q = noulQ))())
        begun     <- transport.begun.get
      } yield {
        assertEquals(dynamic.answers.noul("q"), Right(NoulAnswer(0.9)))
        assertEquals(typed.answers.q, NoulAnswer(0.9))
        assertEquals(begun, 2, "each operation performed exactly one exchange")
      }
    program.unsafeRun
  }

  // spec: effect-portability — the ask pipeline exists once: the request is
  // rendered from config + question set, exchanged under the configured
  // policy, decoded through the ONE decoder, and wrapped with the model,
  // the cost, and the trace the Evaluation concept names.
  test("the-ask-pipeline-renders-exchanges-and-decodes-once") {
    val program =
      for {
        clock     <- ManualClock.init
        transport <- StandInTransport.init(List(Step.Answer(okResponse)), Step.Answer(okResponse))
        client     = new ClientCio(config, transport, clock)
        eval      <- client.systemOneDynamic("subject")(Seq("q" -> noulQ))
        requests  <- transport.requests
      } yield {
        assertEquals(eval.answers.noul("q"), Right(NoulAnswer(0.9)))
        assertEquals(eval.model, "answer-model")
        assertEquals(eval.usage, Usage(Some(10L), Some(5L)))
        assertEquals(eval.requestId, RequestId.fromHeader("req-1"))
        val req = requests.head
        assertEquals(req.method, HttpMethod.Post)
        assertEquals(req.target, "https://unit.test/v1/systemone")
        assert(req.headers.contains("Authorization" -> "Bearer test-key"), "the credential's authorization is carried")
        assert(req.headers.contains("x-tenant" -> "oracle"), "a configured header is carried")
        assert(
          req.body.exists(b => b.contains("\"test-model\"") && b.contains("\"noul\"") && b.contains("\"q\"")),
          s"the wire body does not carry the asked question: ${req.body}"
        )
      }
    program.unsafeRun
  }

  // spec: effect-portability — the typed surface composes the same
  // pipeline: wire keys are the named-tuple field names and the answer
  // comes back assembled by position into `AnswerOf`-typed fields.
  test("the-typed-surface-assembles-answerof-typed-fields") {
    val program =
      for {
        clock     <- ManualClock.init
        transport <- StandInTransport.init(List(Step.Answer(okResponse)), Step.Answer(okResponse))
        client     = new ClientCio(config, transport, clock)
        eval      <- client.systemOne("subject")((q = noulQ))
        requests  <- transport.requests
      } yield {
        assertEquals(eval.answers.q, NoulAnswer(0.9))
        assertEquals(eval.model, "answer-model")
        assert(
          requests.head.body.exists(_.contains("\"q\"")),
          "the wire key is the field name, not a rewritten one"
        )
      }
    program.unsafeRun
  }

  // spec: question-model (re-asserted at the portability layer) — a
  // question set that fails the local limits fails the call BEFORE any
  // request is formed: the transport is never begun.
  test("a-locally-invalid-question-set-fails-before-any-request-is-formed") {
    val program =
      for {
        clock     <- ManualClock.init
        transport <- StandInTransport.init(Nil, Step.Answer(okResponse))
        client     = new ClientCio(config, transport, clock)
        empty     <- client.systemOneDynamic("subject")(Seq.empty).liftToTry
        overLimit <- client
                       .systemOneDynamic("subject")(Seq("s" -> Score(Entry.text("rate"), Vector(Entry.text("only one")))))
                       .liftToTry
        begun     <- transport.begun.get
      } yield {
        assertEquals(begun, 0, "a locally-invalid set reached the wire")
        empty match {
          case Failure(_: InvalidQuestion) => ()
          case other                       => fail(s"an empty set did not fail as InvalidQuestion: $other")
        }
        overLimit match {
          case Failure(_: InvalidQuestion) => ()
          case other                       => fail(s"an over-limit question did not fail as InvalidQuestion: $other")
        }
      }
    program.unsafeRun
  }

  // spec: effect-portability — Scenario: Ordinary failures elsewhere
  // (carrier level). A failure mid-pipeline reaches the caller as a member
  // of the closed family — here the service's own refusal, unaltered.
  test("a-failure-reaches-the-caller-as-a-family-member") {
    val member  = Overloaded(RequestId.fromHeader("req-x"), "overloaded")
    val program =
      for {
        clock     <- ManualClock.init
        transport <- StandInTransport.init(Nil, Step.Fail(member))
        client     = new ClientCio(config, transport, clock)
        outcome   <- client.systemOneDynamic("subject")(Seq("q" -> noulQ)).liftToTry
      } yield assertEquals(outcome, Failure(member))
    program.unsafeRun
  }
}
