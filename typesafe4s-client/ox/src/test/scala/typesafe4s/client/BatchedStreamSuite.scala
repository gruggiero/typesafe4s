package typesafe4s.client

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.{Failure, Try}

import kyo.compat.*
import munit.FunSuite
import ox.supervised

import typesafe4s.{ApiKey, ConcurrencyBound, Entry, HttpResponse, Noul, QuestionSet, RetryPolicy, TypesafeClient, TypesafeException}
import typesafe4s.client.internal.{BatchSetups, StandInTransport}
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// TEST ORACLE — spec: question-batching — Ox row: the published
// `systemOneBatched` returns an `Ox ?=> Flow`; drained inside `supervised`
// it yields every asked name. Carrier-level semantics are covered by the
// shared and parity suites; this suite covers the row's stream adapter:
// answers drain, a batch failure propagates through the stream, and an
// early `take` still reaches `run.close` (the emit fails on the closed
// channel, running the session's finally).
// ============================================================================
final class BatchedStreamSuite extends FunSuite {

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

  private val state = "a plain state"

  private val qs = QuestionSet(
    "isUrgent"      -> Noul(Entry.text("urgent?")),
    "needsRefund"   -> Noul(Entry.text("refund?")),
    "mentionsLegal" -> Noul(Entry.text("legal?"))
  )

  test("systemOneBatched-emits-every-answer-as-a-Flow") {
    val answers  = qs.names.map(n => s""""$n":{"type":"noul","noul":0.5}""").mkString(",")
    val response = HttpResponse(200, Nil, s"""{"model":"test-model","answers":{$answers}}""")
    val program  =
      for {
        transport <- StandInTransport.init(Nil, Step.Answer(response))
        emitted    = supervised(
                       TypesafeClient
                         .of(config, transport)
                         .systemOneBatched(state)(qs.entries, ConcurrencyBound(4))
                         .runToList()
                     )
      } yield {
        assertEquals(emitted.size, 1, "a fitting set emitted more than one evaluation")
        assertEquals(emitted.head.answers.names, qs.names)
      }
    program.unsafeRun
  }

  // spec: question-batching — a batch that fails after its attempts are
  // spent fails the PUBLISHED stream with that failure.
  test("a-failed-batch-fails-the-published-stream") {
    val qs      = BatchSetups.eachAlone(state, 2)
    val boom    = TypesafeException.InternalServer(500, None, "upstream exploded")
    val program =
      for {
        transport <- StandInTransport.init(
                       List(Step.Answer(BatchSetups.okCovering(qs)), Step.Fail(boom)),
                       Step.Fail(new AssertionError("an extra exchange ran"))
                     )
        attempt    = Try(
                       supervised(
                         TypesafeClient
                           .of(config, transport)
                           .systemOneBatched(state)(qs.entries, ConcurrencyBound(1))
                           .runToList()
                       )
                     )
        begun     <- transport.begun.get
      } yield {
        attempt match {
          case Failure(e) => assertEquals(e, boom)
          case _          => fail("a failed batch did not fail the stream")
        }
        assertEquals(begun, 2, "the failing batch's exchange never ran")
      }
    program.unsafeRun
  }

  // spec: question-batching — abandoning after one emission (take(1))
  // closes the session: meter-blocked workers see the abandon flag and no
  // further exchange begins (≤ 2 tolerates a worker that passed the flag
  // check in the same instant close landed — the shared suite's bound).
  // The drain is forked: a lazy stream only starts its session when it is
  // consumed, so the drain must already be running before the first
  // exchange can be observed parked on its gate.
  test("abandoning-the-published-stream-stops-further-exchanges") {
    val qs      = BatchSetups.eachAlone(state, 3)
    val program =
      for {
        g1        <- CPromise.init[Unit]
        g2        <- CPromise.init[Unit]
        g3        <- CPromise.init[Unit]
        transport <- StandInTransport.init(
                       List(
                         Step.Parked(g1, BatchSetups.okCovering(qs)),
                         Step.Parked(g2, BatchSetups.okCovering(qs)),
                         Step.Parked(g3, BatchSetups.okCovering(qs))
                       ),
                       Step.Fail(new AssertionError("an extra exchange ran"))
                     )
        draining  <- CFiber.init(
                       CIO.blocking(
                         supervised(
                           TypesafeClient
                             .of(config, transport)
                             .systemOneBatched(state)(qs.entries, ConcurrencyBound(1))
                             .take(1)
                             .runToList()
                         )
                       )
                     )
        _         <- BatchSetups.countReached(transport.waitingNow.get, 1)
        _         <- g1.succeed(())
        emitted   <- draining.get
        _         <- g2.succeed(())
        _         <- g3.succeed(())
        _         <- CIO.sleep(100.millis) // let any wrongly-forked worker act
        begun     <- transport.begun.get
      } yield {
        assertEquals(emitted.size, 1, "the first batch's evaluation was not emitted")
        assert(begun <= 2, s"exchanges kept starting after the stream was abandoned: $begun")
      }
    program.unsafeRun
  }
}
