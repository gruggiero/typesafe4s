package typesafe4s.client

import scala.concurrent.duration.{Duration, FiniteDuration}

/**
  * When a client stops calling a failing service (#50). After `failureThreshold` calls in a row fail with a failure the retry policy
  * classifies as transient (a 5xx or 529, a 429, a timeout or a lost connection), the breaker opens: for `coolDown`, every call fails at
  * once with `TypesafeException.CircuitOpen`, sending nothing. Then one trial call is let through: if it succeeds the breaker closes, if
  * it fails it opens for another `coolDown`; calls made while the trial is in flight fail fast too.
  *
  * The breaker counts calls, not attempts: a call that succeeds after retries is a success. A refusal such as a 400 or 401 shows the
  * service is up and closes the count. One breaker serves every call a client makes.
  */
final case class CircuitBreakerPolicy(failureThreshold: Int, coolDown: FiniteDuration) {
  require(failureThreshold > 0, s"failureThreshold must be positive, got $failureThreshold")
  require(coolDown > Duration.Zero, s"coolDown must be positive, got $coolDown")
}
