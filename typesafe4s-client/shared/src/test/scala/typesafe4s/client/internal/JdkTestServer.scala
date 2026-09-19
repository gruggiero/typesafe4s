package typesafe4s.client.internal

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters.*

import com.sun.net.httpserver.{HttpExchange, HttpServer}

// ============================================================================
// LOOPBACK TEST SERVER — spec: http-transport (change: add-typesafe4s-sdk,
// spec 5/8)
//
// A `com.sun.net.httpserver` server bound to 127.0.0.1 on an ephemeral port —
// the only way to observe what the JDK transport actually puts on the wire
// (headers, body, method) without leaving the JVM. This is NOT the "network"
// the spec's scenarios forbid: the stand-in exchange discharges the
// no-network scenarios; these fixtures prove the platform default performs a
// real exchange.
// ============================================================================
private[typesafe4s] object JdkTestServer {

  /**
    * What one received request looked like on the wire.
    */
  final case class Recorded(headers: Map[String, String], body: String)

  final class Server private[JdkTestServer] (server: HttpServer, latest: AtomicReference[Recorded]) {

    def port: Int = server.getAddress.getPort

    def stop(): Unit = server.stop(0)

    /**
      * Headers of the most recent request, names lowercased, values joined.
      */
    def lastRequestHeaders(): Map[String, String] =
      Option(latest.get()).fold(Map.empty[String, String])(_.headers)

    /**
      * Body of the most recent request.
      */
    def lastRequestBody(): String =
      Option(latest.get()).fold("")(_.body)
  }

  /**
    * A server that answers every request with `status` and `body`, recording
    * the most recent request it received.
    */
  def start(status: Int, body: String): Server = {
    val latest = new AtomicReference[Recorded]()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      (exchange: HttpExchange) => {
        val requestBody = new String(exchange.getRequestBody.readAllBytes(), UTF_8)
        val headers     = exchange.getRequestHeaders.asScala.map { case (n, vs) =>
          n.toLowerCase(java.util.Locale.ROOT) -> vs.asScala.mkString(",")
        }.toMap
        latest.set(Recorded(headers, requestBody))
        val bytes       = body.getBytes(UTF_8)
        exchange.getResponseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    new Server(server, latest)
  }

  /**
    * A port nothing is listening on — bound then released, so connecting to
    * it is refused. (A momentary rebind by another process is possible but
    * vanishingly unlikely on loopback.)
    */
  def closedPort(): Int = {
    val socket = new java.net.ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }
}
