package typesafe4s.client

import java.util.concurrent.atomic.AtomicLong

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{AnswerSet, ApiKey, Entry, Evaluation, HttpResponse, Noul, NoulAnswer, RetryPolicy, Usage}
import typesafe4s.client.internal.{ClientCio, ManualClock, StandInTransport}
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// #49 — the opt-in response cache: a hit makes no request and returns an
// identical Evaluation; errors are never cached; the key never contains
// the API key; it is off by default.
// ============================================================================
final class ResponseCacheSuite extends FunSuite {

  @unused private given ExecutionContext = munitExecutionContext

  private val secret = "sk-cached-secret"

  private val ok = HttpResponse(
    200,
    List("x-typesafe-request-id" -> "req-1"),
    """{"model":"m","answers":{"q":{"type":"noul","noul":0.7}},"usage":{"input_tokens":3,"output_tokens":1}}"""
  )

  private val unavailable = HttpResponse(503, Nil, "down")

  private def config(cache: Option[ResponseCache], model: String = "m") = TypesafeConfig(
    apiKey = ApiKey.of(secret),
    baseUrl = "https://unit.test",
    model = model,
    allowance = 30.seconds,
    retryPolicy = RetryPolicy.of(attempts = 0, totalBound = None),
    headers = Nil,
    logLevel = LogLevel.Error,
    cache = cache
  )

  private def question(text: String) = Seq("q" -> Noul(Entry.text(text)))

  // records the keys it is asked for
  final private class Spy(underlying: ResponseCache) extends ResponseCache {
    @volatile var keys: List[String]                              = Nil
    def get(key: String): Option[Evaluation[AnswerSet]]           = { keys = keys :+ key; underlying.get(key) }
    def put(key: String, evaluation: Evaluation[AnswerSet]): Unit = underlying.put(key, evaluation)
  }

  private def client(script: List[Step], cfg: TypesafeConfig) =
    for {
      clock     <- ManualClock.init
      transport <- StandInTransport.init(script, Step.Answer(ok))
    } yield (new ClientCio(cfg, transport, clock), transport)

  test("a hit makes no request and returns an identical Evaluation") {
    val program = for {
      (c, transport) <- client(Nil, config(Some(ResponseCache.inMemory(10, 1.minute))))
      first          <- c.systemOneDynamic("state")(question("same?"))
      second         <- c.systemOneDynamic("state")(question("same?"))
      typed          <- c.systemOne("state")((q = Noul(Entry.text("same?"))))
      begun          <- transport.begun.get
    } yield {
      assertEquals(begun, 1)
      assertEquals(second, first)
      assertEquals(typed.answers.q, NoulAnswer(0.7))
    }
    program.unsafeRun
  }

  test("a different state, question or model is a different key") {
    val program = for {
      (c, transport) <- client(Nil, config(Some(ResponseCache.inMemory(10, 1.minute))))
      _              <- c.systemOneDynamic("state")(question("a?"))
      _              <- c.systemOneDynamic("other state")(question("a?"))
      _              <- c.systemOneDynamic("state")(question("b?"))
      _              <- c.withModelForTest("m2").systemOneDynamic("state")(question("a?"))
      begun          <- transport.begun.get
    } yield assertEquals(begun, 4)
    program.unsafeRun
  }

  test("errors are never cached") {
    val program = for {
      (c, transport) <- client(List(Step.Answer(unavailable)), config(Some(ResponseCache.inMemory(10, 1.minute))))
      first          <- c.systemOneDynamic("state")(question("q?")).liftToTry
      second         <- c.systemOneDynamic("state")(question("q?"))
      third          <- c.systemOneDynamic("state")(question("q?"))
      begun          <- transport.begun.get
    } yield {
      assert(first.isFailure)
      assertEquals(third, second)
      assertEquals(begun, 2)
    }
    program.unsafeRun
  }

  test("the cache key never contains the API key") {
    val spy     = new Spy(ResponseCache.inMemory(10, 1.minute))
    val program = for {
      (c, _) <- client(Nil, config(Some(spy)))
      _      <- c.systemOneDynamic(secret)(question(secret))
    } yield {
      assertEquals(spy.keys.size, 1)
      assert(spy.keys.forall(k => !k.contains(secret) && k.matches("[0-9a-f]{64}")), spy.keys)
    }
    program.unsafeRun
  }

  test("off by default: every call asks the service") {
    val program = for {
      (c, transport) <- client(Nil, config(None))
      _              <- c.systemOneDynamic("state")(question("q?"))
      _              <- c.systemOneDynamic("state")(question("q?"))
      begun          <- transport.begun.get
    } yield assertEquals(begun, 2)
    program.unsafeRun
  }

  test("a cache that throws is a miss and never fails the call") {
    val broken  = new ResponseCache {
      def get(key: String): Option[Evaluation[AnswerSet]]           = throw new RuntimeException("store down")
      def put(key: String, evaluation: Evaluation[AnswerSet]): Unit = throw new RuntimeException("store down")
    }
    val program = for {
      (c, transport) <- client(Nil, config(Some(broken)))
      result         <- c.systemOneDynamic("state")(question("q?"))
      begun          <- transport.begun.get
    } yield {
      assertEquals(result.answers.noul("q"), Right(NoulAnswer(0.7)))
      assertEquals(begun, 1)
    }
    program.unsafeRun
  }

  private val evaluation = Evaluation(AnswerSet(Map.empty, "m", Usage.empty, None), "m", Usage.empty, None)

  test("in memory: an entry expires after its ttl") {
    val now   = new AtomicLong(0L)
    val cache = new ResponseCache.InMemory(10, 1.second, () => now.get)
    cache.put("k", evaluation)
    now.set(999.millis.toNanos)
    assertEquals(cache.get("k"), Some(evaluation))
    now.set(1.second.toNanos)
    assertEquals(cache.get("k"), None)
  }

  test("in memory: when full the least recently used entry is evicted") {
    val cache = new ResponseCache.InMemory(2, 1.minute, () => 0L)
    cache.put("a", evaluation)
    cache.put("b", evaluation)
    cache.get("a")
    cache.put("c", evaluation)
    assertEquals(List("a", "b", "c").map(cache.get(_).isDefined), List(true, false, true))
  }

  extension (c: ClientCio) private def withModelForTest(model: String): ClientCio = new ClientCio(c.config.copy(model = model), c.transport, c.clock)
}
