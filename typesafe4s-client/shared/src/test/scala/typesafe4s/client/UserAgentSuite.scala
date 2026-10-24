package typesafe4s.client

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{ApiKey, Entry, HttpRequest, HttpResponse, Noul, RetryPolicy}
import typesafe4s.client.internal.{ClientCio, ManualClock, StandInTransport, UserAgent}
import typesafe4s.client.internal.StandInTransport.Step

// #21 — every request carries the SDK's User-Agent; a caller's own is kept
// in front of it. Compiles into every row, so each artifact asserts its own
// backend name.
final class UserAgentSuite extends FunSuite {

  // the ox binding's `unsafeRun` takes an ExecutionContext; the others do not
  @unused private given ExecutionContext = munitExecutionContext

  private def config(headers: List[(String, String)]) = TypesafeConfig(
    apiKey = ApiKey.of("test-key"),
    baseUrl = "https://unit.test",
    model = "test-model",
    allowance = 10.seconds,
    retryPolicy = RetryPolicy.of(attempts = 0, totalBound = None),
    headers = headers,
    logLevel = LogLevel.Error
  )

  private val answer = Step.Answer(
    HttpResponse(200, Nil, """{"model":"m","answers":{"q":{"type":"noul","noul":0.5}},"usage":{"input_tokens":1,"output_tokens":1}}""")
  )

  private val models = Step.Answer(HttpResponse(200, Nil, """{"data":[]}"""))

  // the requests one ask and one model listing send, through the real pipeline
  private def sent(headers: List[(String, String)]): Vector[HttpRequest] = {
    val program =
      for {
        clock     <- ManualClock.init
        transport <- StandInTransport.init(List(answer, models), answer)
        client     = new ClientCio(config(headers), transport, clock)
        _         <- client.systemOneDynamic("subject")(Seq("q" -> Noul(Entry.text("is it?"))))
        _         <- client.listModels.liftToTry
        requests  <- transport.requests
      } yield requests
    scala.concurrent.Await.result(program.unsafeRun, 30.seconds)
  }

  private def userAgents(request: HttpRequest): List[String] =
    request.headers.collect { case (n, v) if n.equalsIgnoreCase("User-Agent") => v }

  test("the token names the SDK version, this row's backend and the Scala version") {
    val backend = BuildInfo.moduleName.stripPrefix("typesafe4s-client-")
    assertEquals(UserAgent.sdk, s"typesafe4s/${BuildInfo.version} ($backend; scala/${BuildInfo.scalaVersion})")
    assert(Set("zio", "ce", "ox", "kyo", "pekko", "future").contains(backend), backend)
    assert(BuildInfo.scalaVersion.startsWith("3."), BuildInfo.scalaVersion)
  }

  test("every request carries exactly one User-Agent: the SDK's") {
    val requests = sent(Nil)
    assertEquals(requests.size, 2, "an ask and a model listing")
    requests.foreach(r => assertEquals(userAgents(r), List(UserAgent.sdk), r.target))
  }

  test("a caller's User-Agent is kept, with the SDK's token after it") {
    sent(List("User-Agent" -> "myapp/2.3")).foreach { r =>
      assertEquals(userAgents(r), List(s"myapp/2.3 ${UserAgent.sdk}"))
    }
  }

  test("the caller's name is matched case-insensitively, and the last one wins") {
    assertEquals(
      UserAgent.withSdk(List("user-agent" -> "first/1", "x-tenant" -> "t", "USER-AGENT" -> " second/2 ")),
      List("x-tenant" -> "t", "User-Agent" -> s"second/2 ${UserAgent.sdk}")
    )
  }

  test("a blank caller User-Agent is replaced by the SDK's alone") {
    assertEquals(UserAgent.withSdk(List("User-Agent" -> "  ")), List("User-Agent" -> UserAgent.sdk))
  }

  test("other configured headers are untouched") {
    val r = sent(List("x-tenant" -> "oracle")).head
    assert(r.headers.contains("x-tenant" -> "oracle"), r.headers.toString)
    assert(r.headers.contains("Authorization" -> "Bearer test-key"), "the credential is still carried")
  }
}
