package typesafe4s.client.internal

import kyo.compat.*

import typesafe4s.{HttpRequest, HttpResponse}
import typesafe4s.client.Transport

// ============================================================================
// STAND-IN EXCHANGE — spec: http-transport (change: add-typesafe4s-sdk, 5/8)
// Implementation Anchors: "Stand-in exchange — test support — scripted
// responses; compiles into every row".
//
// Answers from a prepared script: each exchange consumes the next `Step` —
// `Answer` returns a response, `Fail` raises an error, `Parked` blocks on a
// test-held gate so the exchange stays in flight until the test releases
// it. Written once against the carrier; the same stand-in drives the shared
// suite and the per-row parity suite.
//
// The counters are the observables the spec's scenarios assert on:
//   begun     — an exchange started (the property counts these)
//   completed — an exchange settled, by answer or failure
//   aborted   — a parked exchange was abandoned before it settled: the
//               ONLY signal "the exchange in flight was stopped" a stand-in
//               can offer without a network
//   waitingNow— exchanges currently parked on a gate
//   requests  — every request the exchange was handed, in order
//   released  — `release` was invoked (spec 7 lifecycle observable)
// ============================================================================
final private[typesafe4s] class StandInTransport private (
  state: CAtomicRef[(Vector[StandInTransport.Step], Vector[HttpRequest])],
  fallback: StandInTransport.Step,
  val begun: CAtomicInt,
  val completed: CAtomicInt,
  val aborted: CAtomicInt,
  val waitingNow: CAtomicInt,
  val released: CAtomicInt
) extends Transport {

  // `requests` and `steps` share ONE atomic cell: recording the request and
  // consuming the step are a single update, so `requests(i)` is provably
  // the exchange that consumed step i — concurrent workers cannot reorder
  // the mapping between record and consume.
  def requests: CIO[Vector[HttpRequest]] = state.get.map(_._2)

  def exchange(request: HttpRequest): CIO[HttpResponse] =
    for {
      _    <- begun.incrementAndGet
      step <- state
                .getAndUpdate { case (steps, reqs) =>
                  (if (steps.isEmpty) steps else steps.tail, reqs :+ request)
                }
                .map(_._1.headOption.getOrElse(fallback))
      resp <- perform(step)
    } yield resp

  // spec: client-configuration — the observable "release happened": the
  // counter the lifecycle oracle asserts on.
  override def release: CIO[Unit] = released.incrementAndGet.unit

  private def perform(step: StandInTransport.Step): CIO[HttpResponse] =
    step match {
      case StandInTransport.Step.Answer(response)        => completed.incrementAndGet.map(_ => response)
      case StandInTransport.Step.Fail(error)             => completed.incrementAndGet.flatMap(_ => CIO.fail(error))
      case StandInTransport.Step.Parked(gate, response)  => parked(gate, response)
      // spec: question-batching — a release-ordered failure: the batch that
      // fails AFTER an earlier batch's answers were emitted, deterministically
      case StandInTransport.Step.ParkedFail(gate, error) =>
        parkedFail(gate, error)
    }

  /**
    * An exchange that stays in flight until `gate` is released. If the wait
    * is abandoned first, `settled` is still false when the ensure runs, so
    * the exchange is recorded `aborted` — a completed wait is never
    * double-counted as an abandonment.
    */
  private def parked(gate: CPromise[Unit], response: HttpResponse): CIO[HttpResponse] =
    CAtomicBoolean.init(false).flatMap { settled =>
      waitingNow.incrementAndGet.flatMap { _ =>
        CIO.ensure(
          settled.get.flatMap { done =>
            (if (done) CIO.unit else aborted.incrementAndGet.unit).flatMap(_ => waitingNow.decrementAndGet.unit)
          }
        )(
          gate.get.flatMap(_ => settled.set(true)).flatMap(_ => completed.incrementAndGet).map(_ => response)
        )
      }
    }

  private def parkedFail(gate: CPromise[Unit], error: Throwable): CIO[HttpResponse] =
    CAtomicBoolean.init(false).flatMap { settled =>
      waitingNow.incrementAndGet.flatMap { _ =>
        CIO.ensure(
          settled.get.flatMap { done =>
            (if (done) CIO.unit else aborted.incrementAndGet.unit).flatMap(_ => waitingNow.decrementAndGet.unit)
          }
        )(
          gate.get
            .flatMap(_ => settled.set(true))
            .flatMap(_ => completed.incrementAndGet)
            .flatMap(_ => CIO.fail(error))
        )
      }
    }
}

private[typesafe4s] object StandInTransport {

  /**
    * What the stand-in does when asked to perform one exchange.
    */
  enum Step {
    case Answer(response: HttpResponse)
    case Fail(error: Throwable)
    case Parked(gate: CPromise[Unit], response: HttpResponse)
    case ParkedFail(gate: CPromise[Unit], error: Throwable)
  }

  /**
    * A stand-in answering `script` in order, then `fallback` forever once
    * the script runs out — a retrying call cannot run off the end.
    */
  def init(script: List[Step], fallback: Step): CIO[StandInTransport] =
    for {
      state     <- CAtomicRef.init((script.toVector, Vector.empty[HttpRequest]))
      begun     <- CAtomicInt.init(0)
      completed <- CAtomicInt.init(0)
      aborted   <- CAtomicInt.init(0)
      waiting   <- CAtomicInt.init(0)
      released  <- CAtomicInt.init(0)
    } yield new StandInTransport(state, fallback, begun, completed, aborted, waiting, released)
}
