package typesafe4s.client

import java.net.{Authenticator, InetSocketAddress, PasswordAuthentication, ProxySelector}
import java.net.http.HttpClient
import java.util.concurrent.Executors

import scala.annotation.unused
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.jdk.DurationConverters.*
import scala.jdk.OptionConverters.*

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{ApiKey, HttpMethod, HttpRequest}
import typesafe4s.client.internal.JdkTestServer

// #30 — the default JDK transport can be configured: connect timeout,
// proxy (with an authenticator), HTTP version and executor each reach the
// HttpClient the SDK builds and owns; the default is unchanged.
final class JdkHttpTransportSettingsSuite extends FunSuite {

  // the ox binding's `unsafeRun` takes an ExecutionContext; the others do not
  @unused private given ExecutionContext = munitExecutionContext

  private val key = ApiKey.of("sk-settings")

  private def none = JdkHttpTransport.build(None, None, None, None, None)

  test("each setting reaches the built HttpClient") {
    val proxy         = ProxySelector.of(new InetSocketAddress("proxy.example", 3128))
    val authenticator = new Authenticator {
      override def getPasswordAuthentication: PasswordAuthentication =
        new PasswordAuthentication("user", "pass".toCharArray)
    }
    val executor      = Executors.newSingleThreadExecutor()
    val client        = JdkHttpTransport.build(
      connectTimeout = Some(3.seconds),
      proxy = Some(proxy),
      authenticator = Some(authenticator),
      version = Some(HttpClient.Version.HTTP_1_1),
      executor = Some(executor)
    )
    try {
      assertEquals(client.connectTimeout().toScala.map(_.toScala), Some(3.seconds))
      assertEquals(client.proxy().toScala, Some(proxy))
      assertEquals(client.authenticator().toScala, Some(authenticator))
      assertEquals(client.version(), HttpClient.Version.HTTP_1_1)
      assertEquals(client.executor().toScala, Some(executor))
    } finally {
      client.close()
      executor.shutdown()
    }
  }

  test("a setting left out keeps the JDK's default, as the default transport does") {
    val client  = none
    val default = HttpClient.newHttpClient()
    try {
      assertEquals(client.connectTimeout().toScala, None)
      assertEquals(client.proxy().toScala, default.proxy().toScala)
      assertEquals(client.authenticator().toScala, None)
      assertEquals(client.version(), default.version())
      assertEquals(client.executor().toScala, None)
    } finally {
      client.close()
      default.close()
    }
  }

  test("a non-positive connect timeout is refused, naming the setting") {
    List(Duration.Zero, Duration(-1, SECONDS)).foreach { t =>
      val e = intercept[IllegalArgumentException](JdkHttpTransport.configured(connectTimeout = Some(t)))
      assert(e.getMessage.contains("connect timeout"), e.getMessage)
    }
  }

  test("a configured transport performs the exchange") {
    val server = JdkTestServer.start(200, """{"ok":true}""")
    try {
      val transport = JdkHttpTransport.configured(connectTimeout = Some(5.seconds), version = Some(HttpClient.Version.HTTP_1_1))
      val program   =
        for {
          resp <- transport.exchange(HttpRequest.exchange(HttpMethod.Get, s"http://127.0.0.1:${server.port}/v1/models", None, key))
          _    <- transport.release
        } yield resp
      val resp      = Await.result(program.unsafeRun, 30.seconds)
      assertEquals(resp.status, 200)
      assertEquals(resp.body, """{"ok":true}""")
    } finally server.stop()
  }

  // a proxy is reached for real: the request names a host that does not
  // resolve, and only the proxy (the local server) can answer it — the
  // server sees that host in the request's Host header
  test("requests go through the configured proxy") {
    val server = JdkTestServer.start(200, """{"via":"proxy"}""")
    try {
      val transport = JdkHttpTransport.configured(proxy = Some(ProxySelector.of(new InetSocketAddress("127.0.0.1", server.port))))
      val program   =
        for {
          resp <- transport.exchange(HttpRequest.exchange(HttpMethod.Get, "http://typesafe.proxy-test.invalid/v1/models", None, key))
          _    <- transport.release
        } yield resp
      val resp      = Await.result(program.unsafeRun, 30.seconds)
      assertEquals(resp.body, """{"via":"proxy"}""")
      assertEquals(server.lastRequestHeaders().get("host"), Some("typesafe.proxy-test.invalid"))
    } finally server.stop()
  }
}
