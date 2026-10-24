package typesafe4s.client

import scala.concurrent.duration.FiniteDuration

import typesafe4s.{HttpMethod, RequestId, TypesafeException, Usage}

/**
  * Callbacks for tracing and metrics (#28): what a call does, as it does it. Override the events you need; every default does nothing.
  * Set one with `config.copy(observer = myObserver)`.
  *
  * Threading: each callback runs inline on the fiber or thread making the call, in the order the events happen, so it must return
  * quickly; hand anything slow (an export, a network write) to your own executor. An exception thrown by a callback is caught and
  * dropped: an observer can never fail a call or change its result.
  *
  * Nothing passed to a callback carries the API key: a call is named by its method and path (never the full URL), and a failure has
  * been scrubbed of the key before any callback sees it. No request or response body is passed.
  */
trait Observer {

  /**
    * An attempt is about to be sent. `attempt` is 1 for the first try of a call, 2 for its first retry.
    */
  def onAttempt(method: HttpMethod, path: String, attempt: Int): Unit = ()

  /**
    * The service answered an attempt with `status`, `elapsed` after the attempt was sent. Called for every status, before it is read as
    * an answer or a refusal.
    */
  def onResponse(method: HttpMethod, path: String, status: Int, elapsed: FiniteDuration): Unit = ()

  /**
    * Attempt `attempt` failed with `failure`, which the retry policy repeats: the call waits `wait`, then sends attempt `attempt + 1`.
    */
  def onRetry(attempt: Int, wait: FiniteDuration, failure: TypesafeException): Unit = ()

  /**
    * A call failed: `failure` is what the caller receives.
    */
  def onFailure(method: HttpMethod, path: String, failure: Throwable): Unit = ()

  /**
    * A `systemOne` call was answered by `model` at the cost of `usage`.
    */
  def onEvaluation(model: String, usage: Usage, requestId: Option[RequestId]): Unit = ()
}

object Observer {

  /**
    * The default: observes nothing.
    */
  val noop: Observer = new Observer {}
}
