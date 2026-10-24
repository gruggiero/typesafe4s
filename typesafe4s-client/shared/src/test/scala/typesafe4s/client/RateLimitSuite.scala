package typesafe4s.client

import java.util.concurrent.ConcurrentLinkedQueue

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{ApiKey, Entry, HttpRequest, HttpResponse, Noul, RetryPolicy}
import typesafe4s.TypesafeException.Timeout
import typesafe4s.client.internal.{ClientCio, Clock, ClockDriver, ManualClock, Permits, StandInTransport}
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// #48 — a client's request rate is capped by its RequestRateLimit: every
// attempt takes a permit on the client's clock, the permits are shared by
// all its calls, and waiting for one counts against the total bound.
// Deterministic on every row: a ManualClock, never wall-clock time.
// ============================================================================
final class RateLimitSuite extends FunSuite {

  @unused private given ExecutionContext = munitExecutionContext

  override val munitTimeout: Duration = 60.seconds

  private val ok = HttpResponse(200, Nil, """{"model":"m","answers":{"q":{"type":"noul","noul":0.5}},"usage":{}}""")

  private val question = Seq("q" -> Noul(Entry.text("ok?")))

  private def config(limit: Option[RequestRateLimit], bound: Option[FiniteDuration] = None) = TypesafeConfig(
    apiKey = ApiKey.of("rate-key"),
    baseUrl = "https://unit.test",
    model = "m",
    allowance = 1.hour,
    retryPolicy = RetryPolicy.of(attempts = 0, totalBound = bound),
    headers = Nil,
    logLevel = LogLevel.Error,
    rateLimit = limit
  )

  // records the clock's reading as each request is sent
  final private class Stamped(clock: Clock, underlying: Transport) extends Transport {
    val sent                                              = new ConcurrentLinkedQueue[FiniteDuration]()
    def exchange(request: HttpRequest): CIO[HttpResponse] =
      clock.monotonic.flatMap(now => CIO.defer { sent.add(now); () }).flatMap(_ => underlying.exchange(request))
    def all: List[FiniteDuration]                         = sent.asScala.toList
  }

  private def calls(n: Int, limit: Option[RequestRateLimit]) =
    for {
      clock    <- ManualClock.init
      stand    <- StandInTransport.init(Nil, Step.Answer(ok))
      transport = new Stamped(clock, stand)
      client    = new ClientCio(config(limit), transport, clock)
      // one call after another: the driver then releases exactly one permit wait at a time
      _        <- ClockDriver.run(clock, 1.minute)((1 to n).foldLeft(CIO.unit)((acc, _) => acc.flatMap(_ => client.systemOneDynamic("s")(question).unit)))
    } yield transport.all

  test("permits reserved together are spaced by the interval") {
    val permits = new Permits(RequestRateLimit(perSecond = 5))
    val waits   = List.fill(6)(permits.reserve(Duration.Zero, None))
    assertEquals(waits, List(0, 200, 400, 600, 800, 1000).map(ms => Some(ms.millis)))
  }

  test("a burst is sent back to back, then the rate applies") {
    val permits = new Permits(RequestRateLimit(perSecond = 5, burst = 3))
    val waits   = List.fill(5)(permits.reserve(Duration.Zero, None))
    assertEquals(waits, List(0, 0, 0, 200, 400).map(ms => Some(ms.millis)))
  }

  test("a permit that could not be had before the deadline is not reserved") {
    val permits = new Permits(RequestRateLimit(perSecond = 1))
    assertEquals(permits.reserve(Duration.Zero, Some(1.second)), Some(Duration.Zero))
    assertEquals(permits.reserve(Duration.Zero, Some(1.second)), None)
    assertEquals(permits.reserve(Duration.Zero, Some(2.seconds)), Some(1.second))
  }

  test("with a limit of 5 per second no second ever sees more than 5 requests") {
    calls(16, Some(RequestRateLimit(perSecond = 5))).map { sent =>
      assertEquals(sent.size, 16)
      sent.foreach(start => assert(sent.count(t => t >= start && t < start + 1.second) <= 5, sent))
      assertEquals(sent.last, 3.seconds)
    }.unsafeRun
  }

  test("without a limit requests are not paced") {
    calls(4, None).map(sent => assertEquals(sent, List.fill(4)(Duration.Zero))).unsafeRun
  }

  test("waiting for a permit counts against the total bound") {
    val program = for {
      clock  <- ManualClock.init
      stand  <- StandInTransport.init(Nil, Step.Answer(ok))
      client  = new ClientCio(config(Some(RequestRateLimit(perSecond = 1)), bound = Some(500.millis)), stand, clock)
      first  <- client.systemOneDynamic("s")(question).liftToTry
      second <- ClockDriver.run(clock, 1.minute)(client.systemOneDynamic("s")(question)).liftToTry
      begun  <- stand.begun.get
    } yield {
      assert(first.isSuccess, first)
      assert(second.failed.toOption.exists(_.isInstanceOf[Timeout]), second)
      assertEquals(begun, 1, "the second request was never sent")
    }
    program.unsafeRun
  }
}
