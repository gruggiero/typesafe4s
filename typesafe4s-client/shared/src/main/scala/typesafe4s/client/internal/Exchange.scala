package typesafe4s.client.internal

import scala.concurrent.duration.FiniteDuration

import kyo.compat.*

import typesafe4s.{HttpRequest, HttpResponse, RequestId, RetryPolicy, TypesafeException}
import typesafe4s.TypesafeException.{InternalServer, Timeout}
import typesafe4s.client.Transport

// ============================================================================
// spec: http-transport — the call pipeline: per-attempt allowance inside
// the retry loop, refusal mapping, abandonment propagation.
//
// ONE pipeline, written once against the carrier — it names no backend.
// Time and waiting go through the `Clock` seam, never `CIO.sleep` /
// `CIO.timeout` directly, so every timing assertion is deterministic on
// every row under ManualClock.
// ============================================================================
private[typesafe4s] object Exchange {

  /**
    * One call: `Retry.run` over attempts, each attempt being the exchange
    * bounded by `allowance` measured on `clock`. Returns the successful
    * (2xx) response; raises the family member for refusals and
    * `Timeout(allowance)` when an attempt's allowance elapses.
    *
    * `operation` is re-evaluated per attempt, so every attempt races the
    * exchange against a FRESH `clock.sleep(allowance)` — the full
    * allowance, never the remainder of a shared deadline (Scenario:
    * Several attempts each get the full allowance).
    */
  def run(
    transport: Transport,
    policy: RetryPolicy,
    clock: Clock,
    allowance: FiniteDuration
  )(request: HttpRequest): CIO[HttpResponse] =
    Retry.run(policy, clock)(attempt(transport, clock, allowance)(request))

  /**
    * One attempt: the exchange raced against `clock.sleep(allowance)` —
    * NEVER `CIO.timeout`, which would read the backend's real clock.
    *
    * When the allowance wins, the exchange branch is abandoned: on rows
    * that can abandon, the transport's release bracket runs (the JDK
    * transport's `cancel(true)`); on the pekko row the exchange keeps
    * running unobserved — the spec's declared no-op. When the exchange
    * wins, the abandoned sleep's waiter is deregistered by the clock.
    */
  private def attempt(
    transport: Transport,
    clock: Clock,
    allowance: FiniteDuration
  )(request: HttpRequest): CIO[HttpResponse] =
    // `CIO.race` diverges across bindings on a losing FAILURE: the zio
    // binding's race joins the surviving branch (first SUCCESS wins),
    // while ce/future return the first completion — a Timeout raised as a
    // failure cannot beat a parked exchange there. So the race is run on
    // successes only: the exchange's outcome is reified with `liftToTry`
    // (always a success) and the allowance is a `None` sentinel, then the
    // winner is re-raised. First completion now wins identically on every
    // row, and the loser is still abandoned the same way.
    //
    // The whole composition sits behind `CIO.defer` — a real suspension —
    // so the race and BOTH branch computations are rebuilt each time
    // `operation` is re-evaluated by Retry. Without it a binding may fuse
    // the construction at `operation` creation and replay the first
    // attempt's forked branches on every retry (the kyo row does).
    CIO.defer(()).flatMap { _ =>
      CIO
        .race(
          transport.exchange(request).flatMap(r => accept(request, r)).liftToTry.map(Some(_)),
          clock.sleep(allowance).map(_ => None)
        )
        .flatMap {
          case Some(result) => CIO.get(result)
          case None         => CIO.fail(Timeout(allowance))
        }
    }

  /**
    * Reads a completed exchange (contract T12): a 2xx answer is returned
    * for spec 6 to decode; a refusal (status >= 400) is raised as its
    * family member via `fromResponse` so Retry's classification sees it;
    * a non-2xx below 400 (1xx/3xx — the JDK client never follows
    * redirects) is raised as `InternalServer` carrying the status — not
    * a refusal, not an answer.
    */
  private def accept(request: HttpRequest, response: HttpResponse): CIO[HttpResponse] =
    response.status match {
      case s if s >= 200 && s < 300 => CIO.value(response)
      case s                        =>
        // a refusal's detail and trace carry what the service sent —
        // which can echo the credential back. The exchange's own
        // credential scrubs every service-provided string before a
        // member stores it (spec: client-configuration — no failure
        // renders the secret; Gate-1 amendment A3, applied caller-side
        // so `fromResponse`'s spec-1 signature is preserved)
        val scrubbed = response.copy(
          headers = response.headers.map { case (n, v) => n -> request.redact(v) },
          body = request.redact(response.body)
        )
        if (s >= 400) CIO.fail(TypesafeException.fromResponse(s, scrubbed.headers, scrubbed.body))
        else CIO.fail(InternalServer(s, requestId(scrubbed), scrubbed.body))
    }

  private[internal] def requestId(response: HttpResponse): Option[RequestId] =
    response.headers
      .collectFirst { case (name, value) if name.equalsIgnoreCase("x-typesafe-request-id") => value }
      .flatMap(RequestId.fromHeader)
}
