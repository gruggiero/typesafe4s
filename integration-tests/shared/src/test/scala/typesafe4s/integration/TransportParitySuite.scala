package typesafe4s.integration

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{ApiKey, HttpMethod, HttpRequest, HttpResponse, RetryPolicy}
import typesafe4s.TypesafeException.*
import typesafe4s.client.{BuildInfo, Transport}
import typesafe4s.client.internal.{JdkTestServer, ManualClock, RetryLoopDriver, StandInTransport, TransportDriver}
import typesafe4s.client.internal.StandInTransport.Step
import typesafe4s.client.internal.TransportDriver.AbandonPoint

/**
  * Ring 5 — spec: http-transport (change: add-typesafe4s-sdk, spec 5/8).
  *
  * The transport seam, the JDK default and the call pipeline are written
  * once against the carrier and compiled into every backend row, so this
  * suite asserts the SAME observable behaviour on zio, ce, ox, kyo and
  * pekko: identical headers on the wire, identical per-attempt allowances,
  * identical refusal mapping, identical exchange counts.
  *
  * The single declared divergence is caller abandonment: on the pekko row a
  * raced-away call cannot be canceled (the carrier lowers to `Future`), so
  * the spec's no-op outcome is asserted there — the abandoned call keeps
  * running — while every other row must observe the exchange stopped.
  */
final class TransportParitySuite extends FunSuite {

  // DECLARED DIVERGENCE: the Ox binding's `unsafeRun` takes an ExecutionContext; the other four bindings do not. A shared suite must
  // therefore supply one, and mark it unused so the rows that ignore it still compile under -Werror -Wunused.
  @unused private given ExecutionContext = munitExecutionContext

  override val munitTimeout: Duration = 60.seconds

  private val isPekko: Boolean = BuildInfo.moduleName.endsWith("-pekko")

  private def key: ApiKey          = ApiKey.of("sk-parity")
  private def request: HttpRequest = HttpRequest.exchange(HttpMethod.Post, "/v1/systemone", Some("{}"), key)
  private def okResponse           = HttpResponse(200, List("x-typesafe-request-id" -> "rid-parity"), """{"answers":{}}""")
  private def refusal              = HttpResponse(503, Nil, "overloaded")

  private def policy(attempts: Int, bound: Option[FiniteDuration] = None): RetryPolicy =
    RetryPolicy.of(attempts = attempts, initialWait = 500.millis, ceiling = 5.seconds, jitter = 0.0, totalBound = bound)

  // spec: http-transport — the platform-provided default puts the same
  // required headers on the wire on every row: the credential's
  // authorization, the body declaration, and the per-call header winning
  // over the configured one.
  test("the default exchange puts the same headers on the wire on every row") {
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
        _      <- Transport.default.exchange(req)
        seen   <- CIO.defer(server.lastRequestHeaders())
        body   <- CIO.defer(server.lastRequestBody())
        _      <- CIO.defer(server.stop())
      } yield (seen, body)
    program.map { case (seen, body) =>
      assertEquals(seen("authorization"), s"Bearer sk-parity", s"authorization differs on ${BuildInfo.moduleName}")
      assertEquals(seen("content-type"), "application/json", s"body declaration differs on ${BuildInfo.moduleName}")
      assertEquals(seen("x-tenant"), "call", s"per-call precedence differs on ${BuildInfo.moduleName}")
      assertEquals(body, """{"subject":"s"}""", s"the request body differs on ${BuildInfo.moduleName}")
    }.unsafeRun
  }

  // spec: http-transport — Scenario: Several attempts each get the full
  // allowance — the same driven deltas on every row.
  test("a driven call observes the same per-attempt allowance on every row") {
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
      assertEquals(run.begun, 3, s"the exchange count differs on ${BuildInfo.moduleName}")
      assertEquals(
        run.deltas,
        List(allowance, 500.millis, allowance, 1.second, allowance),
        s"an attempt's allowance differs on ${BuildInfo.moduleName} (deltas ${run.deltas})"
      )
      assert(run.outcome.isFailure, "the call should end on the last Timeout")
      run.outcome match {
        case Failure(Timeout(limit)) => assertEquals(limit, allowance)
        case other                   => fail(s"expected Timeout($allowance), got $other")
      }
    }.unsafeRun
  }

  // spec: http-transport — a refusal is mapped to its family member and
  // retried identically on every row; the stand-in performs every exchange.
  test("a refusal is mapped and retried identically on every row") {
    TransportDriver
      .runToSettlementF(
        policy(attempts = 2),
        10.seconds,
        request,
        List(Step.Answer(refusal), Step.Answer(okResponse)),
        Step.Answer(okResponse)
      )
      .map { run =>
        assertEquals(run.outcome, Success(okResponse), s"the outcome differs on ${BuildInfo.moduleName}")
        assertEquals(run.begun, 2, s"the exchange count differs on ${BuildInfo.moduleName}")
        assertEquals(run.deltas, List(500.millis), s"the wait differs on ${BuildInfo.moduleName}")
      }
      .unsafeRun
  }

  // spec: http-transport — Scenario: An exchange-level fault joins the
  // classification — ConnectionFailed retried identically on every row.
  test("an exchange-level fault is retried identically on every row") {
    val connRefused = ConnectionFailed(new java.io.IOException("refused"))
    TransportDriver
      .runToSettlementF(
        policy(attempts = 1),
        10.seconds,
        request,
        List(Step.Fail(connRefused), Step.Answer(okResponse)),
        Step.Answer(okResponse)
      )
      .map { run =>
        assertEquals(run.outcome, Success(okResponse), s"the outcome differs on ${BuildInfo.moduleName}")
        assertEquals(run.begun, 2, s"the exchange count differs on ${BuildInfo.moduleName}")
      }
      .unsafeRun
  }

  // spec: http-transport — Scenario: Abandoning mid-exchange. On pekko the
  // spec declares the no-op: the exchange keeps running. Everywhere else it
  // must be stopped.
  test("abandoning mid-exchange stops it — or is the declared no-op on pekko") {
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
        assertEquals(run.aborted, 0, s"pekko cannot abandon — the exchange must not report stopped on ${BuildInfo.moduleName}")
        assert(done >= 1, s"pekko declares abandonment a no-op — the exchange must keep running on ${BuildInfo.moduleName}")
      } else {
        assertEquals(run.aborted, 1, s"the in-flight exchange was not stopped on ${BuildInfo.moduleName}")
        assertEquals(run.begunAfter - run.begunAtAbandon, 0, s"an exchange began after abandonment on ${BuildInfo.moduleName}")
      }
    }.unsafeRun
  }

  // spec: http-transport — Scenario: Abandoning stops the repeat loop too.
  test("abandoning during a wait stops the loop — or is the declared no-op on pekko") {
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
        assert(grown > run.begunAtAbandon, s"pekko declares the no-op — the call must keep running on ${BuildInfo.moduleName}")
      else {
        assertEquals(run.begunAfter, run.begunAtAbandon, s"the loop made another attempt on ${BuildInfo.moduleName}")
        assertEquals(run.parkedLeft, 0, s"a wait survived abandonment on ${BuildInfo.moduleName}")
      }
    }.unsafeRun
  }
}
