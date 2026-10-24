package typesafe4s.client.internal

import java.net.URI

import scala.util.Try

import kyo.compat.*

// ============================================================================
// #28 — how the pipeline calls an `Observer`: inline, in event order, with
// any exception the callback throws caught and dropped, so an observer can
// never fail a call or change its result.
// ============================================================================
private[typesafe4s] object Observe {

  def apply(callback: => Unit): CIO[Unit] = CIO.defer(Try(callback)).unit

  /**
    * The path of a request target: what an observer is told instead of the full URL.
    */
  def path(target: String): String = Try(new URI(target).getRawPath).toOption.filter(_ != null).getOrElse("")
}
