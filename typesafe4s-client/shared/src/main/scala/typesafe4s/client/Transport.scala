package typesafe4s.client

import kyo.compat.*

import typesafe4s.{HttpRequest, HttpResponse}

// spec: http-transport — Concepts Introduced: Transport — "single-method
// capability — exchanges a request for a response; the seam a caller may
// replace". PUBLIC (unlike the internal machinery): implementing one
// method swaps in http4s, sttp or zio-http.
trait Transport {

  /**
    * Performs one exchange: sends `request`, returns what the service
    * answered — for ANY status. Status mapping is the pipeline's job, not
    * the seam's, so a caller-supplied transport implements exactly one
    * method and needs no knowledge of the error family.
    *
    * Exchange-level faults (no connection, broken stream) should fail the
    * CIO with a `TypesafeException` member (`ConnectionFailed`) to take
    * part in retry classification; any other failure propagates untried.
    */
  def exchange(request: HttpRequest): CIO[HttpResponse]

  /**
    * Releases what the transport holds (spec: client-configuration — a
    * client releases what it holds). The default is a no-op: the SPI stays
    * one-method, and a caller transport holding nothing releasable (or
    * managing its own lifecycle) implements `exchange` alone. The JDK
    * transport OWNS the `HttpClient` it builds via `apply` — its release
    * closes it; `withClient` is caller-owned, so its release is this
    * default.
    */
  def release: CIO[Unit] = CIO.unit
}

object Transport {

  /**
    * The platform-provided default — used when a caller configured no
    * exchange (Scenario: The default is used when none is chosen). Built
    * on `java.net.http.HttpClient`; draws no third-party exchange library
    * onto the path.
    *
    * A `def`, never a shared `val` (Gate-1 amendment A4): each
    * default-constructed client OWNS its transport, so `release` on one
    * client cannot close a client another still holds.
    */
  def default: Transport = JdkHttpTransport()
}
