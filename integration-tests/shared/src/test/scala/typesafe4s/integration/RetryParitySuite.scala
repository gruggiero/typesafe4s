package typesafe4s.integration

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{RequestId, RetryPolicy, TypesafeException}
import typesafe4s.TypesafeException.*
import typesafe4s.client.BuildInfo
import typesafe4s.client.internal.{ManualClock, Retry, RetryLoopDriver}

/**
  * Ring 5 — spec: retry-policy (change: add-typesafe4s-sdk, spec 4/8).
  *
  * The retry loop is written once against the carrier (`CIO` + `Clock`) and
  * compiled into every backend row, so this suite asserts the SAME observable
  * behaviour on zio, ce, ox, kyo and pekko: identical waits begun, identical
  * attempt counts, identical classification, identical final failure.
  *
  * The single declared divergence is caller abandonment: on the pekko row a
  * raced-away call cannot be canceled (the carrier lowers to `Future`), so
  * the spec's no-op outcome is asserted there — the abandoned call keeps
  * running — while every other row must observe the call stopped.
  */
final class RetryParitySuite extends FunSuite {

  // DECLARED DIVERGENCE: the Ox binding's `unsafeRun` takes an ExecutionContext; the other four bindings do not. A shared suite must
  // therefore supply one, and mark it unused so the rows that ignore it still compile under -Werror -Wunused.
  @unused private given ExecutionContext = munitExecutionContext

  override val munitTimeout: Duration = 60.seconds

  private val isPekko: Boolean = BuildInfo.moduleName.endsWith("-pekko")

  private val rid: Option[RequestId] = RequestId.fromHeader("parity-rid")

  // The closed family, enumerated once — the classification table must read
  // identically on every row.
  private val family: List[(String, TypesafeException)] = List(
    "BadRequest"          -> BadRequest(rid, "malformed"),
    "Authentication"      -> Authentication(rid, "unauthenticated"),
    "PermissionDenied"    -> PermissionDenied(rid, "forbidden"),
    "NotFound"            -> NotFound(rid, "unknown"),
    "UnprocessableEntity" -> UnprocessableEntity(rid, "unprocessable"),
    "RateLimit"           -> RateLimit(rid, Some(3.seconds), "too many"),
    "Overloaded"          -> Overloaded(rid, "overloaded"),
    "InternalServer/408"  -> InternalServer(408, rid, "slow"),
    "InternalServer/503"  -> InternalServer(503, rid, "fault"),
    "InternalServer/451"  -> InternalServer(451, rid, "legal"),
    "ResponseValidation"  -> ResponseValidation(rid, "answers.q", "wrong shape"),
    "ConnectionFailed"    -> ConnectionFailed(new java.io.IOException("refused")),
    "Timeout"             -> Timeout(10.seconds),
    "MissingAnswer"       -> MissingAnswer("q1"),
    "InvalidQuestion"     -> InvalidQuestion(Some("q1"), "over the limit"),
    "MissingCredential"   -> MissingCredential("TYPESAFE_API_KEY")
  )

  // spec: retry-policy — Scenario anchors, identical on every row: the
  // shipped defaults retry transient statuses, prefer service delays, bound
  // the whole call, and report the last failure.
  test("a driven run observes the same waits, attempts and final failure on every row") {
    val policy = RetryPolicy.of(
      attempts = 2,
      initialWait = 500.millis,
      ceiling = 5.seconds,
      jitter = 0.0,
      totalBound = Some(30.seconds)
    )
    val last   = RateLimit(rid, None, "last")
    RetryLoopDriver
      .run(policy)(n => if (n == 3) last else InternalServer(503, rid, "fault"))
      .map { run =>
        assertEquals(run.waitsBegun, List(500.millis, 1.second), "the waits begun differ on this row")
        assertEquals(run.attempts, 3, "the attempt count differs on this row")
        assertEquals(run.outcome, scala.util.Failure(last), "the reported failure differs on this row")
      }
      .unsafeRun
  }

  test("classification is identical on every row") {
    val default = RetryPolicy.default
    val table   = family.map { case (name, e) => name -> default.isRetryable(e) }
    assertEquals(
      table,
      List(
        "BadRequest"          -> false,
        "Authentication"      -> false,
        "PermissionDenied"    -> false,
        "NotFound"            -> false,
        "UnprocessableEntity" -> false,
        "RateLimit"           -> true,
        "Overloaded"          -> true,
        "InternalServer/408"  -> true,
        "InternalServer/503"  -> true,
        "InternalServer/451"  -> false,
        "ResponseValidation"  -> false,
        "ConnectionFailed"    -> true,
        "Timeout"             -> true,
        "MissingAnswer"       -> false,
        "InvalidQuestion"     -> false,
        "MissingCredential"   -> false
      ),
      s"the retry classification differs on ${BuildInfo.moduleName}"
    )
    // spec: retry-policy — "a wait the service asks for": the advised delay
    // is read off the failure identically on every row.
    val advised = family.collect { case (name, e) => name -> default.advisedDelay(e) }
    assertEquals(advised.find(_._1 == "RateLimit").map(_._2), Some(Some(3.seconds)))
    assert(advised.forall { case (n, d) => n == "RateLimit" || d.isEmpty }, "a member other than RateLimit advised a delay")
  }

  // spec: retry-policy — Scenario: The caller goes away mid-wait + "the
  // remaining bound is not consumed". On pekko the spec declares the no-op:
  // the abandoned call keeps running. Everywhere else it must stop.
  test("abandoning the call mid-wait stops it — or is the declared no-op on pekko") {
    val policy  = RetryPolicy.of(
      attempts = 5,
      initialWait = 10.seconds,
      ceiling = 10.seconds,
      jitter = 0.0,
      totalBound = None
    )
    val program =
      for {
        clock    <- ManualClock.init
        counter  <- CAtomicInt.init(0)
        abandoned = RetryLoopDriver.parkedCount(clock, 1)
        op        = counter.incrementAndGet.flatMap(_ => CIO.fail(InternalServer(503, rid, "fault")): CIO[Unit])
        _        <- CIO.race(Retry.run(policy, clock)(op), abandoned)
        // the call was abandoned mid-wait; move the clock far past the
        // deadline so any surviving call would plainly make another attempt
        _        <- clock.advance(60.seconds)
        _        <- if (isPekko) RetryLoopDriver.attemptCount(counter, 2) else CIO.unit
        attempts <- counter.get
      } yield
        if (isPekko)
          assert(attempts >= 2, s"pekko declares abandonment a no-op — the abandoned call must keep running (saw $attempts attempt(s))")
        else
          assertEquals(attempts, 1, s"an abandoned call made another attempt on ${BuildInfo.moduleName}")
    program.unsafeRun
  }
}
