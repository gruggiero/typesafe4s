package typesafe4s.client

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{AnswerSet, ApiKey, ConcurrencyBound, Evaluation, HttpResponse, Question, QuestionSet, RetryPolicy, TokenBudget, TypesafeException}
import typesafe4s.client.internal.{BatchRun, BatchSetups, ClientCio, ManualClock, StandInTransport}
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// TEST ORACLE — spec: question-batching (change: add-typesafe4s-sdk, 8/8)
//
// Written from the spec and the Gate-1 contract BEFORE any implementation.
// Runs the scenarios at the carrier seam (`ClientCio.askBatched` →
// `BatchRun`), so the same suite compiles into and executes on every
// backend row — one write, every row.
//
// Determinism: no wall-clock sleep decides an outcome. The ManualClock
// never advances, so per-exchange allowances never fire; StandInTransport
// gates hold exchanges in flight until the test releases them, and the
// begun/waitingNow counters are the concurrency observables the spec's
// Proof Obligations name.
// ============================================================================
final class QuestionBatchingSuite extends FunSuite {

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

  // --------------------------------------------------------------------------
  // fixtures — a response covering every asked name, so whichever subset a
  // batch carries, the answer decodes; and a sizing helper that makes each
  // question occupy its own batch BY CONSTRUCTION (~70% of the room beside
  // the state → alone it fits, any two together overflow).
  // --------------------------------------------------------------------------

  private def smallQuestion(name: String): (String, Question[?]) = BatchSetups.smallQuestion(name)

  private def est(qs: QuestionSet): Int = BatchSetups.est(state, qs)

  // binary-search an instruction length whose marginal estimate ≈ target
  private def questionSizedTo(name: String, targetTokens: Int): (String, Question[?]) =
    BatchSetups.questionSizedTo(state, name, targetTokens)

  // n questions, each filling ~70% of the budget beside the state → n batches
  private def eachAlone(n: Int): QuestionSet = BatchSetups.eachAlone(state, n)

  private def okCovering(qs: QuestionSet): HttpResponse = BatchSetups.okCovering(qs)

  // pull every emitted evaluation; a failing `next` ends the drain and the
  // failure is returned alongside the prefix already emitted
  private def drain(run: BatchRun): CIO[(List[Evaluation[AnswerSet]], Option[Throwable])] = {
    def loop(acc: List[Evaluation[AnswerSet]]): CIO[(List[Evaluation[AnswerSet]], Option[Throwable])] =
      run.next.liftToTry.flatMap {
        case Success(Some(e)) => loop(e :: acc)
        case Success(None)    => CIO.value((acc.reverse, None))
        case Failure(t)       => CIO.value((acc.reverse, Some(t)))
      }
    loop(Nil)
  }

  // poll an observable until it reaches `expected` — bounded, deterministic
  // on the executing scheduler, same pattern as DeterministicClockSuite
  private def countReached(observe: CIO[Int], expected: Int): CIO[Unit] =
    BatchSetups.countReached(observe, expected)

  // moves the ManualClock forward in steps smaller than the per-attempt
  // allowance (30s), releasing retry waits (~500ms) as they register while
  // never firing an attempt's own allowance; stops when `done` observes the
  // awaited exchange settle
  private def pumpUntil(clock: ManualClock, done: CIO[Boolean]): CIO[Unit] = {
    def loop(attemptsLeft: Int): CIO[Unit] =
      done.flatMap { finished =>
        if (finished) CIO.unit
        else if (attemptsLeft <= 0) CIO.fail(new AssertionError("the pumped exchange never settled"))
        else clock.advance(600.millis).flatMap(_ => CIO.sleep(1.milli)).flatMap(_ => loop(attemptsLeft - 1))
      }
    loop(40)
  }

  // --------------------------------------------------------------------------
  // spec: question-batching — Scenario: Several questions in one exchange —
  // a fitting set travels in ONE exchange and its answers come back keyed
  // by their own names.
  // --------------------------------------------------------------------------
  test("several-questions-one-exchange") {
    val qs      = QuestionSet(
      smallQuestion("isUrgent"),
      smallQuestion("needsRefund"),
      smallQuestion("mentionsLegal")
    )
    val program =
      for {
        clock            <- ManualClock.init
        transport        <- StandInTransport.init(Nil, Step.Answer(okCovering(qs)))
        client            = new ClientCio(config, transport, clock)
        batchRun         <- client.askBatched(state, Right(qs), ConcurrencyBound(4))
        (evals, failure) <- drain(batchRun)
        begun            <- transport.begun.get
      } yield {
        assert(failure.isEmpty, s"drain failed: $failure")
        assertEquals(evals.size, 1, "a fitting set emitted more than one evaluation")
        assertEquals(evals.head.answers.names, qs.names, "answers were not keyed by the asked names")
        assertEquals(begun, 1, "a fitting set made more than one exchange")
      }
    program.unsafeRun
  }

  // --------------------------------------------------------------------------
  // spec: question-batching — Scenario: One question that cannot ever fit —
  // refused locally, naming the question, and NO exchange is attempted.
  // --------------------------------------------------------------------------
  test("one-question-that-cannot-ever-fit") {
    val room     = TokenBudget.limit.approximateTokens - est(QuestionSet(smallQuestion("probe")))
    val offender = questionSizedTo("theOffender", room + 500)
    val qs       = QuestionSet(smallQuestion("fine1"), offender, smallQuestion("fine2"))
    val program  =
      for {
        clock     <- ManualClock.init
        transport <- StandInTransport.init(Nil, Step.Answer(okCovering(qs)))
        client     = new ClientCio(config, transport, clock)
        attempt   <- client.askBatched(state, Right(qs), ConcurrencyBound(4)).liftToTry
        begun     <- transport.begun.get
      } yield {
        attempt match {
          case Failure(e: TypesafeException.InvalidQuestion) =>
            assert(e.name.contains("theOffender"), s"the refusal did not name the question: $e")
          case other                                         => fail(s"expected a local InvalidQuestion refusal, got: $other")
        }
        assertEquals(begun, 0, "an exchange was attempted for a question that cannot fit")
      }
    program.unsafeRun
  }

  // spec: question-batching — a set that fails QuestionSet's own limits
  // (arriving as Left through the seam, as `systemOneBatched` produces it)
  // fails before any exchange.
  test("an-invalid-set-fails-before-any-exchange") {
    val invalid = TypesafeException.InvalidQuestion(None, "exceeds the question-set limit")
    val program =
      for {
        clock     <- ManualClock.init
        transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(200, Nil, "{}")))
        client     = new ClientCio(config, transport, clock)
        attempt   <- client.askBatched(state, Left(invalid), ConcurrencyBound(4)).liftToTry
        begun     <- transport.begun.get
      } yield {
        assertEquals(attempt, Failure(invalid), "the invalid set did not fail the call")
        assertEquals(begun, 0, "an exchange was attempted for an invalid set")
      }
    program.unsafeRun
  }

  // --------------------------------------------------------------------------
  // spec: question-batching — Scenario: Answers arrive as they are ready.
  // Three own-batch questions parked on three gates; release the SECOND
  // exchange's gate first — its evaluation must arrive before the first
  // batch's. Arrival order, not forced batch order (Gate-1 Q1).
  // The step↔batch mapping is read from the recorded requests, so the test
  // is deterministic whatever order workers reach the transport.
  // --------------------------------------------------------------------------
  test("answers-arrive-as-they-are-ready") {
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
        _         <- g2.succeed(())
        first     <- batchRun.next
        reqs      <- transport.requests
        _         <- g1.succeed(())
        _         <- g3.succeed(())
        _         <- batchRun.close
      } yield {
        // the second-requested exchange was released — its answers arrive first
        val releasedNames = qs.names.filter(n => reqs(1).body.exists(_.contains(s""""$n"""")))
        assert(
          first.exists(_.answers.names == releasedNames),
          s"the released batch's answers were not emitted first: $first vs $releasedNames"
        )
      }
    program.unsafeRun
  }

  // --------------------------------------------------------------------------
  // spec: question-batching — Scenario: A batch that fails — a batch that
  // fails after its attempts are spent fails the whole stream with that
  // failure; answers already emitted remain usable. ParkedFail gates the
  // failure behind a release, so the emission order is deterministic.
  // --------------------------------------------------------------------------
  test("a-batch-that-fails") {
    val qs      = eachAlone(2)
    val boom    = TypesafeException.InternalServer(500, None, "upstream exploded")
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
        _         <- batchRun.close
      } yield {
        assert(first.isDefined, "the first batch's answers were never emitted")
        val (emitted, failure) = rest
        assert(failure.contains(boom), s"the stream did not fail with the batch's failure: $failure")
        // the earlier emission remains a perfectly usable evaluation
        assert(first.get.answers.answers.nonEmpty, "the emitted answers were unusable")
        assert(emitted.isEmpty, "answers arrived after the failure")
      }
    program.unsafeRun
  }

  // --------------------------------------------------------------------------
  // spec: question-batching — Scenario: How many run at once is bounded and
  // chosen. Bound 2 over four own-batch questions parked on gates: no more
  // than two exchanges ever in flight; releasing one admits the next.
  // --------------------------------------------------------------------------
  test("how-many-run-at-once-is-bounded") {
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

  // spec: question-batching — the bound is CALLER-SELECTED: a different
  // choice changes how many exchanges run at once.
  test("the-bound-is-chosen-by-the-caller") {
    val qs      = eachAlone(3)
    val program =
      for {
        clock     <- ManualClock.init
        gates     <- CIO.collectAll(List.fill(3)(CPromise.init[Unit]))
        transport <- StandInTransport.init(
                       gates.toSeq.map(g => Step.Parked(g, okCovering(qs))).toList,
                       Step.Fail(new AssertionError("an extra exchange ran"))
                     )
        client     = new ClientCio(config, transport, clock)
        batchRun  <- client.askBatched(state, Right(qs), ConcurrencyBound(1))
        _         <- countReached(transport.waitingNow.get, 1)
        begun     <- transport.begun.get
        _         <- CIO.foreachDiscard(gates.toSeq)(_.succeed(()).unit)
        _         <- batchRun.close
      } yield assertEquals(begun, 1, "a bound of 1 admitted more than one exchange")
    program.unsafeRun
  }

  // --------------------------------------------------------------------------
  // spec: question-batching — abandoning the stream stops further exchanges:
  // a worker checks the abandoned signal before issuing its exchange. On
  // rows whose carrier can interrupt, close also aborts the in-flight
  // exchange; on the Future/Pekko row the in-flight exchange cannot be
  // preempted — the declared divergence (Ring-5 table, exercised per row by
  // the parity suite).
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
        batchRun  <- client.askBatched(state, Right(qs), ConcurrencyBound(1))
        _         <- countReached(transport.waitingNow.get, 1)
        _         <- g1.succeed(())
        first     <- batchRun.next
        _         <- batchRun.close
        // everything released; nowhere may a FURTHER exchange begin
        _         <- g2.succeed(())
        _         <- g3.succeed(())
        _         <- CIO.sleep(50.millis) // let any wrongly-forked worker act
        begun     <- transport.begun.get
      } yield {
        assert(first.isDefined, "the first batch's answers were never emitted")
        assert(begun <= 2, s"exchanges kept starting after the run was closed: $begun")
      }
    program.unsafeRun
  }

  // --------------------------------------------------------------------------
  // spec: question-batching — Requirement: a batch that fails after its
  // attempts are spent fails the stream — the retry machinery still applies
  // inside a batch (Retry/schedule spends attempts, then the failure
  // arrives). Attempts = 1, script: retryable failure then an answer —
  // one batch, two exchanges, answers arrive.
  // --------------------------------------------------------------------------
  test("a-batch-spends-its-attempts-then-answers") {
    val qs        = QuestionSet(smallQuestion("q1"))
    val retryable = TypesafeException.InternalServer(503, None, "transient")
    val program   =
      for {
        clock            <- ManualClock.init
        transport        <- StandInTransport.init(
                              List(Step.Fail(retryable)),
                              Step.Answer(okCovering(qs))
                            )
        client            = new ClientCio(
                              config.copy(retryPolicy = RetryPolicy.of(attempts = 1, totalBound = None)),
                              transport,
                              clock
                            )
        batchRun         <- client.askBatched(state, Right(qs), ConcurrencyBound(4))
        // attempt 1 has failed when `completed` moves; its retry wait is
        // parked on the ManualClock — pump the clock until attempt 2
        // settles, then drain
        _                <- countReached(transport.completed.get, 1)
        _                <- pumpUntil(clock, transport.completed.get.map(_ >= 2))
        (evals, failure) <- drain(batchRun)
        begun            <- transport.begun.get
      } yield {
        assert(failure.isEmpty, s"drain failed: $failure")
        assertEquals(begun, 2, "the batch's retry never re-attempted")
        assertEquals(evals.size, 1)
      }
    program.unsafeRun
  }
}
