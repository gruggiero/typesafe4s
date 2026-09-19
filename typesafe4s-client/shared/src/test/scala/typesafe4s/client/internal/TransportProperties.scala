package typesafe4s.client.internal

import scala.annotation.unused
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

import kyo.compat.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.{classify, forAll}

import typesafe4s.{ApiKey, HttpMethod, HttpRequest, HttpResponse, RetryPolicy}
import typesafe4s.TypesafeException.*
import typesafe4s.client.{BuildInfo, JdkHttpTransport, Transport}
import typesafe4s.client.internal.StandInTransport.Step
import typesafe4s.client.internal.TransportDriver.AbandonPoint

// ============================================================================
// TEST ORACLE (exchange half) — spec: http-transport (change:
// add-typesafe4s-sdk, spec 5/8)
//
// The call pipeline — per-attempt allowance, refusal mapping, abandonment —
// exercised through the stand-in exchange under the ManualClock. Written
// once against the carrier, compiled into every row; the declared pekko
// divergence is branched on `isPekko`, exactly like RetryParitySuite.
// ============================================================================
final class TransportProperties extends ScalaCheckSuite {

  // The Ox binding's `unsafeRun` takes an ExecutionContext; the other
  // bindings do not. A shared suite supplies one and marks it unused so the
  // rows that ignore it still compile under -Werror -Wunused.
  @unused private given ExecutionContext = munitExecutionContext

  override val munitTimeout: Duration = 60.seconds

  private val isPekko: Boolean = BuildInfo.moduleName.endsWith("-pekko")

  // fixtures are defs, not vals — a `???` in the contract must fail each
  // test on its own terms, not poison the suite at class-init
  private def key: ApiKey          = ApiKey.of("sk-oracle")
  private def request: HttpRequest = HttpRequest.exchange(HttpMethod.Post, "/v1/systemone", Some("{}"), key)
  private def okResponse           = HttpResponse(200, List("x-typesafe-request-id" -> "rid-1"), """{"answers":{}}""")
  private def refusal              = HttpResponse(503, Nil, "overloaded")

  private def policy(attempts: Int, bound: Option[FiniteDuration] = None): RetryPolicy =
    RetryPolicy.of(attempts = attempts, initialWait = 500.millis, ceiling = 5.seconds, jitter = 0.0, totalBound = bound)

  // --------------------------------------------------------------------------
  // spec: http-transport — generator strategy `genAbandonPoint`: enumerated,
  // not timed — every point is reached deterministically.
  // --------------------------------------------------------------------------
  private val genAbandonPoint: Gen[AbandonPoint] =
    Gen.oneOf(AbandonPoint.BeforeFirst, AbandonPoint.DuringExchange, AbandonPoint.DuringWait, AbandonPoint.AfterLast)

  // --------------------------------------------------------------------------
  // spec: http-transport — Property: An abandoned call performs no further
  // exchange
  // --------------------------------------------------------------------------
  property("abandoned-call-performs-no-further-exchange") {
    forAll(genAbandonPoint) { point =>
      val program        =
        for {
          clock   <- ManualClock.init
          gate    <- CPromise.init[Unit]
          // every point needs a script that reaches it: a parked exchange
          // covers DuringExchange; a retryable refusal then a parked
          // exchange covers DuringWait; all-Answer covers the rest.
          script   = point match {
                       case AbandonPoint.DuringExchange => List(Step.Parked(gate, okResponse))
                       case AbandonPoint.DuringWait     =>
                         List(Step.Fail(InternalServer(503, None, "fault")), Step.Parked(gate, okResponse))
                       case _                           => List(Step.Answer(okResponse))
                     }
          standIn <- StandInTransport.init(script, Step.Answer(okResponse))
          run     <- TransportDriver.abandonAt(
                       clock,
                       standIn,
                       policy(attempts = 2, bound = Some(30.seconds)),
                       10.seconds,
                       request,
                       point,
                       releaseAfter = gate.succeed(()).unit
                     )
          // DECLARED DIVERGENCE (spec: A row that cannot abandon says so):
          // on pekko the abandoned call keeps running — a bounded poll for
          // the counter that must move is the proof; a point-read would race
          // the survivor sitting between steps.
          _       <- if (isPekko)
                       point match {
                         case AbandonPoint.BeforeFirst | AbandonPoint.DuringExchange =>
                           RetryLoopDriver.attemptCount(standIn.completed, 1)
                         case AbandonPoint.DuringWait                                =>
                           RetryLoopDriver.attemptCount(standIn.begun, run.begunAtAbandon + 1)
                         case AbandonPoint.AfterLast                                 => CIO.unit
                       }
                     else CIO.unit
          aborted <- standIn.aborted.get
        } yield (run, aborted)
      val (run, aborted) = Await.result(program.unsafeRun, 30.seconds)
      classify(true, point.toString) {
        if (isPekko) aborted == 0
        else run.begunAfter == run.begunAtAbandon
      }
    }
  }

  // --------------------------------------------------------------------------
  // Scenarios — one test per spec heading
  // --------------------------------------------------------------------------

  // spec: http-transport — Scenario: A caller supplies their own exchange —
  // every exchange goes through the supplied implementation
  test("a caller-supplied exchange performs every exchange") {
    TransportDriver
      .runToSettlementF(
        policy(attempts = 2),
        10.seconds,
        request,
        List(Step.Answer(refusal), Step.Answer(okResponse)),
        Step.Answer(okResponse)
      )
      .map { run =>
        assertEquals(run.outcome, Success(okResponse), "the stand-in's answer did not come back")
        assertEquals(run.begun, 2, "an exchange did not go through the supplied implementation")
        assertEquals(run.requests.toList, List(request, request), "the call's request was not what was sent")
      }
      .unsafeRun
  }

  // spec: http-transport — Scenario: Exercising the SDK with no network —
  // repeating, failure mapping and reading are all exercised
  test("the SDK is exercised with no network") {
    val connRefused = ConnectionFailed(new java.io.IOException("refused"))
    TransportDriver
      .runToSettlementF(
        policy(attempts = 3),
        10.seconds,
        request,
        List(Step.Answer(refusal), Step.Fail(connRefused), Step.Answer(okResponse)),
        Step.Answer(okResponse)
      )
      .map { run =>
        // repeating: refusal → retried; connection failure → retried; then answered
        assertEquals(run.begun, 3)
        // failure mapping: the 503 entered the error channel as InternalServer and was retried
        assertEquals(run.outcome, Success(okResponse))
        // reading: the response surface is read — status, headers, body
        assertEquals(run.outcome.get.status, 200)
        assertEquals(run.outcome.get.body, """{"answers":{}}""")
      }
      .unsafeRun
  }

  // spec: http-transport — Scenario: The default is used when none is chosen —
  // the platform-provided default performs a real exchange (loopback server;
  // the "no network" scenarios above go through the stand-in).
  test("the platform-provided default performs the exchange") {
    val program =
      for {
        server <- CIO.defer(JdkTestServer.start(200, """{"ok":true}"""))
        resp   <- Transport.default.exchange(
                    HttpRequest.exchange(HttpMethod.Get, s"http://127.0.0.1:${server.port}/v1/models", None, key)
                  )
        _      <- CIO.defer(server.stop())
      } yield resp
    program.map { resp =>
      assertEquals(resp.status, 200)
      assertEquals(resp.body, """{"ok":true}""")
    }.unsafeRun
  }

  // spec: http-transport — Scenario: An exchange carries what the service
  // requires — asserted END-TO-END on the JDK transport: the server observes
  // the authorization, the body declaration and the caller headers.
  test("the default exchange puts the required headers on the wire") {
    val program =
      for {
        server <- CIO.defer(JdkTestServer.start(200, "{}"))
        req     = HttpRequest.exchange(
                    HttpMethod.Post,
                    s"http://127.0.0.1:${server.port}/v1/systemone",
                    Some("""{"subject":"s"}"""),
                    key,
                    configured = List("x-tenant" -> "conf"),
                    perCall = List("x-tenant" -> "call")
                  )
        _      <- JdkHttpTransport().exchange(req)
        seen   <- CIO.defer(server.lastRequestHeaders())
        body   <- CIO.defer(server.lastRequestBody())
        _      <- CIO.defer(server.stop())
      } yield (seen, body)
    program.map { case (seen, body) =>
      assertEquals(seen("authorization"), key.authorization._2)
      assertEquals(seen("content-type"), "application/json")
      assertEquals(seen("x-tenant"), "call")
      assertEquals(body, """{"subject":"s"}""")
    }.unsafeRun
  }

  // spec: http-transport — exchange-level fault mapping: an unreachable
  // service fails as ConnectionFailed (retry classification then applies)
  test("an unreachable service fails as ConnectionFailed") {
    val program =
      for {
        port   <- CIO.defer(JdkTestServer.closedPort())
        result <- JdkHttpTransport()
                    .exchange(HttpRequest.exchange(HttpMethod.Get, s"http://127.0.0.1:$port/v1/models", None, key))
                    .liftToTry
      } yield result
    program.map {
      case Failure(_: ConnectionFailed) => ()
      case other                        => fail(s"expected ConnectionFailed, got $other")
    }.unsafeRun
  }

  // spec: http-transport — exchange-level fault mapping (Ring 8 finding
  // F8): the stage's failure arrives wrapped — CompletionException from
  // sendAsync, ExecutionException from other completion APIs — and only an
  // unwrapped I/O fault joins the classification; anything else propagates
  // untried, and an already-family failure is left alone.
  test("exchange-level fault mapping unwraps the stage's wrapper") {
    val io     = new java.io.IOException("refused")
    JdkHttpTransport.asConnectionFailed(new java.util.concurrent.CompletionException(io)) match {
      case ConnectionFailed(cause) => assertEquals(cause, io)
      case other                   => fail(s"CompletionException(IOException) mapped to $other")
    }
    JdkHttpTransport.asConnectionFailed(new java.util.concurrent.ExecutionException(io)) match {
      case ConnectionFailed(cause) => assertEquals(cause, io)
      case other                   => fail(s"ExecutionException(IOException) mapped to $other")
    }
    val nonIo  = new IllegalStateException("not io")
    assertEquals(JdkHttpTransport.asConnectionFailed(nonIo), nonIo)
    val family = ConnectionFailed(io)
    assertEquals(JdkHttpTransport.asConnectionFailed(family), family)
  }

  // spec: http-transport — Scenario: An attempt runs out of time — fails
  // naming the allowance, and is eligible to be tried again
  test("an attempt that runs out of time fails naming the allowance and is tried again") {
    val allowance = 7.seconds
    val program   =
      for {
        clock   <- ManualClock.init
        gate    <- CPromise.init[Unit]
        standIn <- StandInTransport.init(List(Step.Parked(gate, okResponse)), Step.Parked(gate, okResponse))
        run     <- TransportDriver.runToSettlement(clock, standIn, policy(attempts = 1), allowance, request)
      } yield run
    program.map { run =>
      run.outcome match {
        case Failure(Timeout(limit)) =>
          assertEquals(limit, allowance, "the failure does not name the allowance that elapsed")
        case other                   => fail(s"expected Timeout($allowance), got $other")
      }
      assertEquals(run.begun, 2, "a timed-out attempt was not tried again")
      assert(RetryPolicy.default.isRetryable(Timeout(allowance)), "the timeout failure is not repeatable")
    }.unsafeRun
  }

  // spec: http-transport — Scenario: Several attempts each get the full
  // allowance — each attempt's allowance measured on the ManualClock
  test("several attempts each get the full allowance") {
    val allowance = 7.seconds
    val program   =
      for {
        clock   <- ManualClock.init
        g1      <- CPromise.init[Unit]
        g2      <- CPromise.init[Unit]
        g3      <- CPromise.init[Unit]
        standIn <- StandInTransport.init(
                     List(Step.Parked(g1, okResponse), Step.Parked(g2, okResponse), Step.Parked(g3, okResponse)),
                     Step.Answer(okResponse)
                   )
        run     <- TransportDriver.runToSettlement(clock, standIn, policy(attempts = 2), allowance, request)
      } yield run
    program.map { run =>
      // every exchange timed out: the driver advanced exactly allowance
      // before each of the three attempts, with the policy's derived
      // waits in between
      assertEquals(run.begun, 3)
      assertEquals(
        run.deltas,
        List(allowance, 500.millis, allowance, 1.second, allowance),
        s"an attempt did not get the full allowance (deltas ${run.deltas})"
      )
      assert(run.outcome.isFailure, "the call should end on the last Timeout")
    }.unsafeRun
  }

  // spec: http-transport — Scenario: Abandoning mid-exchange — the exchange
  // is stopped, not left running
  test("abandoning mid-exchange stops the exchange — or is the declared no-op on pekko") {
    val program =
      for {
        clock   <- ManualClock.init
        gate    <- CPromise.init[Unit]
        standIn <- StandInTransport.init(List(Step.Parked(gate, okResponse)), Step.Answer(okResponse))
        run     <- TransportDriver.abandonAt(
                     clock,
                     standIn,
                     policy(attempts = 0),
                     10.seconds,
                     request,
                     AbandonPoint.DuringExchange,
                     releaseAfter = gate.succeed(()).unit
                   )
        _       <- if (isPekko) RetryLoopDriver.attemptCount(standIn.completed, 1) else CIO.unit
        done    <- standIn.completed.get
      } yield (run, done)
    program.map { case (run, done) =>
      if (isPekko) {
        assertEquals(run.aborted, 0, "pekko cannot abandon — the exchange must not report stopped")
        assert(done >= 1, "the abandoned exchange did not keep running")
      } else {
        assertEquals(run.aborted, 1, "the in-flight exchange was not stopped")
        assertEquals(run.begunAfter - run.begunAtAbandon, 0, "an exchange began after abandonment")
      }
    }.unsafeRun
  }

  // spec: http-transport — Scenario: Abandoning stops the repeat loop too —
  // no further attempt, and the bound is not spent
  test("abandoning during a wait makes no further attempt — or is the declared no-op on pekko") {
    val program =
      for {
        clock   <- ManualClock.init
        gate    <- CPromise.init[Unit]
        standIn <- StandInTransport.init(
                     List(Step.Fail(InternalServer(503, None, "fault")), Step.Parked(gate, okResponse)),
                     Step.Answer(okResponse)
                   )
        run     <- TransportDriver.abandonAt(
                     clock,
                     standIn,
                     policy(attempts = 5, bound = Some(30.seconds)),
                     10.seconds,
                     request,
                     AbandonPoint.DuringWait,
                     releaseAfter = gate.succeed(()).unit
                   )
        _       <- if (isPekko) RetryLoopDriver.attemptCount(standIn.begun, run.begunAtAbandon + 1) else CIO.unit
        grown   <- standIn.begun.get
      } yield (run, grown)
    program.map { case (run, grown) =>
      if (isPekko)
        assert(grown > run.begunAtAbandon, "pekko declares the no-op — the call must keep running")
      else {
        assertEquals(run.begunAfter, run.begunAtAbandon, "the repeat loop made another attempt after abandonment")
        assertEquals(run.parkedLeft, 0, "a wait survived abandonment — bound is still being spent")
      }
    }.unsafeRun
  }
}
