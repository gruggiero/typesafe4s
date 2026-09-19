package typesafe4s.client

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import kyo.compat.*
import munit.FunSuite
import zio.Exit

import typesafe4s.{ApiKey, Entry, Noul, RequestId, RetryPolicy, TypesafeClient}
import typesafe4s.TypesafeException.Overloaded
import typesafe4s.client.internal.{ManualClock, StandInTransport}
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// TEST ORACLE (typed channel) — spec: effect-portability (change:
// add-typesafe4s-sdk, spec 6/8)
//
// Written from the spec and the Gate-1 contract BEFORE any implementation.
// ZIO is the one ecosystem with a typed failure channel: the facade's alias
// names `IO[TypesafeException, A]` — the SDK's closed failure family —
// narrowed by `refineToOrDie` in `lower`.
// ============================================================================
final class TypedChannelSuite extends FunSuite {

  @unused private given ExecutionContext = munitExecutionContext

  private val config = TypesafeConfig(
    apiKey = ApiKey.of("test-key"),
    baseUrl = "https://unit.test",
    model = "test-model",
    allowance = 30.seconds,
    retryPolicy = RetryPolicy.of(attempts = 0, totalBound = None),
    headers = Nil,
    logLevel = LogLevel.Error
  )

  private val noulQ = Noul(Entry.text("is it true?"))

  // spec: effect-portability — Scenario: A typed channel where one exists.
  // The signature-level half: the published operation's type names the
  // failure family in the channel. GREEN-BY-DESIGN — the alias holds on
  // the contract.
  test("the-operations-type-names-the-failure-family") {
    assertEquals(
      compileErrors(
        """val c: zio.IO[typesafe4s.TypesafeException, typesafe4s.Evaluation[typesafe4s.AnswerSet]] = typesafe4s.TypesafeClient.of(null).systemOneDynamic("subject")(Seq("q" -> typesafe4s.Noul(typesafe4s.Entry.text("i"))))"""
      ),
      "",
      "the operation's type does not name the failure family in the channel"
    )
  }

  // spec: effect-portability — Scenario: A typed channel where one exists
  // (the runtime half). A service refusal arrives through the channel as a
  // typed failure — not as a defect, and not widened to Throwable.
  test("a-failure-arrives-in-the-typed-channel") {
    val member  = Overloaded(RequestId.fromHeader("req-x"), "overloaded")
    val program =
      for {
        clock     <- ManualClock.init
        transport <- StandInTransport.init(Nil, Step.Fail(member))
        client     = TypesafeClient.of(config, transport)
        exit      <- CIO.lift(client.systemOneDynamic("subject")(Seq("q" -> noulQ)).exit)
      } yield exit match {
        case Exit.Success(_)     => fail("a service refusal came back as a success")
        case Exit.Failure(cause) =>
          assertEquals(
            cause.failureOption,
            Some(member),
            "the refusal was not a typed failure in the channel"
          )
      }
    program.unsafeRun
  }
}
