package typesafe4s.integration

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{AnswerSet, ApiKey, ConcurrencyBound, Entry, Evaluation, HttpResponse, Noul, Question, QuestionSet, RetryPolicy, TokenBudget}
import typesafe4s.client.{BuildInfo, LogLevel, TypesafeConfig}
import typesafe4s.client.internal.{BatchRun, ClientCio, ManualClock, StandInTransport}
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// CROSS-BACKEND PARITY — spec: question-batching (add-typesafe4s-sdk, 8/8)
//
// The Ring-5 table, exercised per row over the carrier session triple
// (`BatchRun` — the single shared batching runtime). One file compiled into
// every published row; each binding's own scheduler/meter/promise semantics
// execute the assertions, so a divergence in in-flight accounting or
// arrival order is a per-row failure.
//
//   answers emitted as each batch resolves — SAME SET, observed batch order
//   a failed batch fails the stream — yes on every row, prefix usable
//   concurrency bound — holds on every row, caller-chosen
//   abandoning stops further exchanges — yes ×4; Pekko CANNOT preempt an
//     in-flight exchange on the Future carrier — the declared divergence,
//     observed as `aborted == 0` while a released parked exchange completes.
//
// Determinism: gates + counters, never wall-clock sampling (ManualClock
// parked forever means allowances never fire).
// ============================================================================
final class QuestionBatchingParitySuite extends FunSuite {

  @unused private given ExecutionContext = munitExecutionContext

  private def config: TypesafeConfig = TypesafeConfig(
    apiKey = ApiKey.of("test-key"),
    baseUrl = "https://unit.test",
    model = "test-model",
    allowance = 30.seconds,
    retryPolicy = RetryPolicy.of(attempts = 0, totalBound = None),
    headers = Nil,
    logLevel = LogLevel.Error
  )

  private val state: String = "a plain state"

  private def est(qs: QuestionSet): Int = TokenBudget.estimate(state, qs).approximateTokens

  private def marginal(chars: Int): Int =
    est(QuestionSet("q" -> Noul(Entry.text("x" * chars)))) -
      est(QuestionSet("q" -> Noul(Entry.text(""))))

  private def questionSizedTo(name: String, targetTokens: Int): (String, Question[?]) = {
    @scala.annotation.tailrec
    def search(lo: Int, hi: Int): Int =
      if (hi - lo <= 1) lo
      else {
        val mid = lo + (hi - lo) / 2
        if (marginal(mid) <= targetTokens) search(mid, hi) else search(lo, mid)
      }
    name -> Noul(Entry.text("x" * search(0, 300_000)))
  }

  private def eachAlone(n: Int): QuestionSet = {
    val room = TokenBudget.limit.approximateTokens - est(QuestionSet("p" -> Noul(Entry.text("probe"))))
    val qs   = (1 to n).toList.map(i => questionSizedTo(s"qqq$i", (room * 7) / 10))
    QuestionSet(qs.head, qs.tail*)
  }

  private def okCovering(qs: QuestionSet): HttpResponse = {
    val answers = qs.names.map(n => s""""$n":{"type":"noul","noul":0.5}""").mkString(",")
    HttpResponse(200, Nil, s"""{"model":"test-model","answers":{$answers}}""")
  }

  private def drain(run: BatchRun): CIO[(List[Evaluation[AnswerSet]], Option[Throwable])] = {
    def loop(acc: List[Evaluation[AnswerSet]]): CIO[(List[Evaluation[AnswerSet]], Option[Throwable])] =
      run.next.liftToTry.flatMap {
        case Success(Some(e)) => loop(e :: acc)
        case Success(None)    => CIO.value((acc.reverse, None))
        case Failure(t)       => CIO.value((acc.reverse, Some(t)))
      }
    loop(Nil)
  }

  private def countReached(observe: CIO[Int], expected: Int): CIO[Unit] = {
    def loop(attemptsLeft: Int): CIO[Unit] =
      observe.flatMap { n =>
        if (n >= expected) CIO.unit
        else if (attemptsLeft <= 0) CIO.fail(new AssertionError(s"expected $expected, saw $n"))
        else CIO.sleep(1.milli).flatMap(_ => loop(attemptsLeft - 1))
      }
    loop(5000)
  }

  private val pekkoRow: Boolean = BuildInfo.moduleName.endsWith("-pekko")

  // --------------------------------------------------------------------------
  // Ring-5 row 1: answers emitted as each batch resolves — the same set,
  // in batch order when batches resolve in order.
  // --------------------------------------------------------------------------
  test("answers-emit-per-batch-same-set-same-order") {
    val qs      = eachAlone(3)
    val program =
      for {
        clock     <- ManualClock.init
        g1        <- CPromise.init[Unit]
        g2        <- CPromise.init[Unit]
        g3        <- CPromise.init[Unit]
        transport <- StandInTransport.init(
                       List(
                         Step.Parked(g1, okCovering(qs)),
                         Step.Parked(g2, okCovering(qs)),
                         Step.Parked(g3, okCovering(qs))
                       ),
                       Step.Fail(new AssertionError("an extra exchange ran"))
                     )
        client     = new ClientCio(config, transport, clock)
        batchRun  <- client.askBatched(state, Right(qs), ConcurrencyBound(3))
        _         <- countReached(transport.waitingNow.get, 3)
        _         <- g1.succeed(())
        first     <- batchRun.next
        _         <- g2.succeed(())
        second    <- batchRun.next
        _         <- g3.succeed(())
        third     <- batchRun.next
        done      <- batchRun.next
        reqs      <- transport.requests
        begun     <- transport.begun.get
      } yield {
        val namesOf = (i: Int) => qs.names.filter(n => reqs(i).body.exists(_.contains(s""""$n"""")))
        assertEquals(first.map(_.answers.names), Some(namesOf(0)), "first emitted batch is not the first released")
        assertEquals(second.map(_.answers.names), Some(namesOf(1)), "second emitted batch is not the second released")
        assertEquals(third.map(_.answers.names), Some(namesOf(2)), "third emitted batch is not the third released")
        assert(done.isEmpty, "the run did not terminate after all batches")
        assertEquals(begun, 3, "exchange count != batch count")
        val union   = List(first, second, third).flatMap(_.toList).flatMap(_.answers.names).toSet
        assertEquals(union, qs.names, "emitted answers do not cover the question set")
      }
    program.unsafeRun
  }

  // --------------------------------------------------------------------------
  // Ring-5 row 2: a failed batch fails the stream with that failure;
  // earlier emitted answers remain usable — on every row.
  // --------------------------------------------------------------------------
  test("a-failed-batch-fails-the-stream") {
    val qs      = eachAlone(2)
    val boom    = typesafe4s.TypesafeException.InternalServer(500, None, "parity boom")
    val program =
      for {
        clock     <- ManualClock.init
        gOk       <- CPromise.init[Unit]
        gFail     <- CPromise.init[Unit]
        transport <- StandInTransport.init(
                       List(Step.Parked(gOk, okCovering(qs)), Step.ParkedFail(gFail, boom)),
                       Step.Fail(new AssertionError("an extra exchange ran"))
                     )
        client     = new ClientCio(config, transport, clock)
        batchRun  <- client.askBatched(state, Right(qs), ConcurrencyBound(1))
        _         <- countReached(transport.waitingNow.get, 1)
        _         <- gOk.succeed(())
        first     <- batchRun.next
        _         <- countReached(transport.waitingNow.get, 1)
        _         <- gFail.succeed(())
        rest      <- drain(batchRun)
      } yield {
        assert(first.exists(_.answers.answers.nonEmpty), "the emitted answers were unusable")
        assert(rest._2.contains(boom), s"the stream did not fail with the batch's failure: ${rest._2}")
      }
    program.unsafeRun
  }

  // --------------------------------------------------------------------------
  // Ring-5 row 3: the concurrency bound holds — and it is caller-chosen.
  // --------------------------------------------------------------------------
  test("concurrency-bound-respected") {
    val qs      = eachAlone(4)
    val program =
      for {
        clock     <- ManualClock.init
        gates     <- CIO.collectAll(List.fill(4)(CPromise.init[Unit]))
        transport <- StandInTransport.init(
                       gates.toSeq.map(g => Step.Parked(g, okCovering(qs))).toList,
                       Step.Fail(new AssertionError("an extra exchange ran"))
                     )
        client     = new ClientCio(config, transport, clock)
        batchRun  <- client.askBatched(state, Right(qs), ConcurrencyBound(2))
        _         <- countReached(transport.waitingNow.get, 2)
        begun     <- transport.begun.get
        _         <- CIO.foreachDiscard(gates.toSeq)(_.succeed(()).unit)
        _         <- batchRun.close
      } yield assertEquals(begun, 2, "more exchanges began than the bound allows")
    program.unsafeRun
  }

  // --------------------------------------------------------------------------
  // Ring-5 row 4: abandoning stops further exchanges — yes on four rows,
  // NO on Pekko/Future: the in-flight exchange cannot be preempted, so the
  // parked exchange is never `aborted` — it completes when released.
  // Declared divergence, asserted as such.
  // --------------------------------------------------------------------------
  test("abandoning-stops-further-exchanges") {
    val qs      = eachAlone(3)
    val program =
      for {
        clock     <- ManualClock.init
        g1        <- CPromise.init[Unit]
        g2        <- CPromise.init[Unit]
        g3        <- CPromise.init[Unit]
        transport <- StandInTransport.init(
                       List(
                         Step.Parked(g1, okCovering(qs)),
                         Step.Parked(g2, okCovering(qs)),
                         Step.Parked(g3, okCovering(qs))
                       ),
                       Step.Fail(new AssertionError("an extra exchange ran"))
                     )
        client     = new ClientCio(config, transport, clock)
        batchRun  <- client.askBatched(state, Right(qs), ConcurrencyBound(2))
        _         <- countReached(transport.waitingNow.get, 2)
        _         <- batchRun.close
        _         <- CIO.foreachDiscard(List(g1, g2, g3))(_.succeed(()).unit)
        _         <- CIO.sleep(50.millis)
        begun     <- transport.begun.get
        aborted   <- transport.aborted.get
      } yield {
        if (pekkoRow) {
          // declared divergence: in-flight exchanges cannot be preempted;
          // released gates let them COMPLETE — nothing is recorded aborted
          assertEquals(aborted, 0, "the Future row reported an aborted exchange")
        } else {
          assert(aborted >= 1, "abandoned in-flight exchanges were not aborted")
        }
        assert(begun <= 2, s"exchanges kept starting after close: $begun")
      }
    program.unsafeRun
  }
}
