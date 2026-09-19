package typesafe4s.client.typecontract

import java.net.http.HttpClient

import scala.concurrent.duration.FiniteDuration

import kyo.compat.*

import typesafe4s.{HttpRequest, HttpResponse, RetryPolicy}
import typesafe4s.client.{JdkHttpTransport, Transport}
import typesafe4s.client.internal.{Clock, Exchange}

// ============================================================================
// TYPED CONTRACT — spec: http-transport (change: add-typesafe4s-sdk, spec 5/8)
//
// Gate 1 artifact, APPROVED 2026-09-18. The declarations were promoted to
// typesafe4s-client/shared/src/main/scala/typesafe4s/client/{Transport,
// JdkHttpTransport}.scala and internal/Exchange.scala at Step 3; this file
// remains as a living compile-time assertion of the approved surface — if
// the implementation drifts from it, this file stops compiling.
//
// Approved surface (Gate 1):
//   trait Transport — def exchange(request: HttpRequest): CIO[HttpResponse]
//     PUBLIC: the caller-replaceable capability (Concepts Introduced)
//   object Transport — val default: Transport (the platform-provided default)
//   object JdkHttpTransport — def apply(): Transport;
//     def withClient(client: HttpClient): Transport
//   private[typesafe4s] object internal.Exchange —
//     run(transport, policy, clock, allowance)(request): CIO[HttpResponse]
//
// Approved design decisions:
//   T10 `exchange` returns the response faithfully for ANY completed
//       exchange — status mapping is the pipeline's job, not the seam's, so
//       a caller-supplied transport implements exactly one method and needs
//       no knowledge of the error family. The Transport scaladoc states:
//       fail the CIO with a TypesafeException member to take part in retry
//       classification; anything else propagates untried.
//   T11 The abandonment bracket lives INSIDE JdkHttpTransport.exchange:
//       `acquireReleaseWith(acquire = sendAsync)(release = cancel(true))` —
//       kyo.compat's `fromCompletionStage` does not pass interruption back
//       to the stage on the future binding (design.md §6; verified against
//       the kyo-compat-future sources), so the transport holds the
//       CompletableFuture and cancels it on release. The JDK client honours
//       cancel(true) by aborting the exchange. On the pekko/future rows no
//       cancellation exists — release still runs when the stage completes
//       and cancel(true) on a completed future is a harmless no-op.
//   T12 `Exchange.run` composes what a "call" means at this spec: one
//       attempt = the exchange raced against `clock.sleep(allowance)` —
//       the allowance is measured against the Clock seam (deterministic on
//       every row under ManualClock; NEVER CIO.timeout, which would read
//       the backend's real clock). The loser's abandonment is T11's bracket
//       — or the declared no-op on pekko. A completed refusal (status >=
//       400) is raised as its family member via fromResponse so Retry's
//       classification sees it; a non-2xx below 400 (1xx/3xx — the JDK
//       client never follows redirects by default) is raised as
//       InternalServer carrying the status: not a refusal, not an answer.
//   T13 `Exchange.run` returns the successful (2xx) HttpResponse — decoding
//       belongs to Evaluation/ask (spec 6), not to the transport.
//   T14 Timeout(allowance) is raised when the allowance elapses — it names
//       the elapsed limit (Scenario: An attempt runs out of time) and is
//       already a member Retry/classify repeats via retryOnTimeout.
//   T15 `Transport.default` IS JdkHttpTransport() — "the platform-provided
//       default is used" when the caller configured none. The client
//       construction that consults it lands in spec 6/7; the value is
//       declared now so the default is a named, testable thing.
//   T16 `private[typesafe4s]` on Exchange: the public surface that a caller
//       configures is Transport itself; the retrying pipeline is SDK
//       machinery underneath — same disposition as Retry.run (L1).
// ============================================================================

private object TransportTypeContract {

  // surface witnesses — stop compiling if the promoted surface drifts.
  // Defs, not vals: nothing evaluates at class-init.
  def witnessExchange(transport: Transport, request: HttpRequest): CIO[HttpResponse] =
    transport.exchange(request)
  def witnessDefault: Transport                                                      = Transport.default
  def witnessJdk: Transport                                                          = JdkHttpTransport()
  def witnessJdkClient(client: HttpClient): Transport                                = JdkHttpTransport.withClient(client)
  def witnessRun(
    transport: Transport,
    policy: RetryPolicy,
    clock: Clock,
    allowance: FiniteDuration,
    request: HttpRequest
  ): CIO[HttpResponse]                                                               =
    Exchange.run(transport, policy, clock, allowance)(request)
}
