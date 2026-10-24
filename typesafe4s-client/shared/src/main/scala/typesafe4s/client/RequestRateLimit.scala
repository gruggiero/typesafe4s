package typesafe4s.client

/**
  * A cap on the rate at which one client sends requests (#48): on average `perSecond` requests a second, with up to `burst` sent back to
  * back after a quiet spell. Every attempt, retries included, takes a permit before it is sent, so all fibers sharing a client share the
  * cap. With `burst = 1` the requests are spaced evenly and no window of one second ever holds more than `perSecond` of them.
  *
  * Waiting for a permit counts against the retry policy's total bound: a permit that cannot be had before the bound would end is not
  * waited for, and the call fails with `Timeout`.
  */
final case class RequestRateLimit(perSecond: Int, burst: Int = 1) {
  require(perSecond > 0, s"perSecond must be positive, got $perSecond")
  require(burst > 0, s"burst must be positive, got $burst")
}
