package typesafe4s.client.internal

import kyo.compat.*

/**
  * Build stub, and the load-bearing one. This file lives in the SHARED source tree, so it is compiled into EVERY backend row: zio, ce, ox,
  * kyo, pekko, and the unpublished future anchor. It names no backend, which is the rule every shared source must follow.
  *
  * `CIO[A]` is an opaque type resolving to a different concrete effect per row, and every operation is an `inline def`, so this compiles to
  * native primitives with no wrapper objects. If the compat wiring is wrong for any row, this file is where it fails first.
  */
private[typesafe4s] object CarrierProbe {

  /**
    * Lifts a pure value into the carrier.
    */
  def value[A](a: A): CIO[A] = CIO.value(a)

  /**
    * Proves the carrier composes, not merely that it exists.
    */
  def mapped(n: Int): CIO[Int] = CIO.value(n).map(_ + 1)

  /**
    * Proves failure is expressible in the carrier's error channel.
    */
  def failed(error: Throwable): CIO[Nothing] = CIO.fail(error)
}
