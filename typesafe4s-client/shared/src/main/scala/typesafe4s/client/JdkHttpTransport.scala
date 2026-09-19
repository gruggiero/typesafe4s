package typesafe4s.client

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CompletionException, ExecutionException}

import scala.jdk.CollectionConverters.*

import kyo.compat.*

import typesafe4s.{HttpMethod, HttpRequest, HttpResponse}
import typesafe4s.TypesafeException.ConnectionFailed

// spec: http-transport — Implementation Anchors: JdkHttpTransport —
// "default implementation; built on java.net.http.HttpClient; adds no
// dependency". Written once against the carrier: the same source compiles
// into every backend row.
object JdkHttpTransport {

  /**
    * A transport on a fresh `HttpClient.newHttpClient()` — OWNED: its
    * `release` closes the client it built (spec: client-configuration —
    * a client releases what it holds).
    */
  def apply(): Transport = {
    val client = HttpClient.newHttpClient()
    new Transport {
      def exchange(request: HttpRequest): CIO[HttpResponse] = send(client, request)

      // `HttpClient.close` (JDK 21+) shuts the client's own resources
      // down; an exchange attempted afterwards fails at sendAsync
      override def release: CIO[Unit] = CIO.defer(client.close())
    }
  }

  /**
    * A transport on a caller-configured client — proxies, authenticators
    * and connect timeouts are the caller's; the exchange and its
    * abandonment bracket are the SDK's. CALLER-OWNED: `release` is the
    * trait's default no-op — the SDK never closes what it did not build.
    */
  def withClient(client: HttpClient): Transport =
    new Transport {
      def exchange(request: HttpRequest): CIO[HttpResponse] = send(client, request)
    }

  /**
    * One exchange: `sendAsync` for the request, the response read as a
    * String.
    *
    * The abandonment bracket lives INSIDE exchange (contract T11): the
    * `CompletableFuture` is acquired as a resource whose release is
    * `cancel(true)` — `fromCompletionStage` on bindings that do not pass
    * interruption back would leave the stage running; the bracket's
    * release stops it on every row that can abandon, and `cancel(true)`
    * on a completed stage is a harmless no-op on the pekko/future rows.
    *
    * No request-level timeout is set: the per-attempt allowance is the
    * pipeline's job (`Exchange.run` races the exchange against the Clock
    * seam) — a second clock here would compete with the deterministic
    * one the tests drive.
    */
  private def send(client: HttpClient, request: HttpRequest): CIO[HttpResponse] =
    CIO.acquireReleaseWith(
      acquire = CIO.defer(client.sendAsync(jdkRequest(request), JdkResponse.BodyHandlers.ofString(UTF_8)))
    )(
      release = stage => CIO.defer(stage.cancel(true)).unit
    )(
      use = stage =>
        // `liftToTry`/`get` reifies the stage's completion then re-raises it:
        // on bindings that surface an exceptional stage outside the
        // recoverable error channel (kyo reports it as a panic), this is the
        // only contract-sanctioned way to bring the failure back where
        // `mapError`/`recover` can see it. Semantics are unchanged on rows
        // whose stage failures already arrive recoverably.
        CIO
          .fromCompletionStage(stage)
          .map(toResponse)
          .liftToTry
          .flatMap(t => CIO.get(t))
          .mapError(asConnectionFailed)
    )

  // spec: http-transport — exchange-level faults join the retry
  // classification as ConnectionFailed; the stage reports them wrapped in
  // CompletionException (or ExecutionException on other completion APIs),
  // so the cause is unwrapped first. Anything that is not an I/O fault
  // propagates untried (contract T10). `private[client]` — the oracle
  // exercises the mapping directly.
  private[client] val asConnectionFailed: Throwable => Throwable = {
    case e: ConnectionFailed => e
    case e                   =>
      unwrap(e) match {
        case io: java.io.IOException => ConnectionFailed(io)
        case other                   => other
      }
  }

  private[client] def unwrap(e: Throwable): Throwable =
    e match {
      case ce: CompletionException if ce.getCause != null => ce.getCause
      case ee: ExecutionException if ee.getCause != null  => ee.getCause
      case other                                          => other
    }

  private def jdkRequest(request: HttpRequest): JdkRequest = {
    val builder   = JdkRequest.newBuilder(URI.create(request.target))
    request.headers.foreach { case (name, value) => builder.header(name, value) }
    val publisher = request.body match {
      case Some(body) => JdkRequest.BodyPublishers.ofString(body, UTF_8)
      case None       => JdkRequest.BodyPublishers.noBody()
    }
    request.method match {
      case HttpMethod.Get  => builder.GET()
      case HttpMethod.Post => builder.POST(publisher)
    }
    builder.build()
  }

  private def toResponse(response: JdkResponse[String]): HttpResponse =
    HttpResponse(
      response.statusCode(),
      response
        .headers()
        .map()
        .asScala
        .flatMap { case (name, values) => values.asScala.map(name -> _) }
        .toList,
      response.body()
    )
}
