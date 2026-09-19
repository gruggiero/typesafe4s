package typesafe4s.client.internal

import scala.util.{Failure, Success, Try}

import kyo.compat.*

import typesafe4s.{AnswerSet, ConcurrencyBound, Evaluation, QuestionSet, StateEncoder, TokenBudget, TypesafeException}
import typesafe4s.client.{Transport, TypesafeConfig}

// ============================================================================
// spec: question-batching — Implementation Anchors: "Bounded concurrency —
// shared runtime over the carrier — typesafe4s-client/shared".
//
// The batched exchange as a SESSION over the carrier: `start` acquires the
// worker set (bounded by `CMeter` on `concurrency`, results flowing through
// a `CChannel`), `next` pulls one batch's evaluation — `None` when every
// batch is accounted for — and `close` signals abandonment so a worker
// checks the flag BEFORE issuing its exchange and an in-flight exchange
// races against the abandon latch. Pekko's Future carrier cannot preempt
// the loser of that race — the declared divergence.
//
// A session triple, not a bare CStream: the carrier stream has no
// acquire/release primitive, so early abandonment could not reach the
// workers; and the future row's CStream is a pull repr — the pekko facade
// adapts the triple (lazy acquire + unfold + termination watch), not the
// repr.
// ============================================================================
private[typesafe4s] trait BatchRun {

  // Some per batch as it resolves — the CIO fails when a batch's failure
  // arrives after Retry/schedule has spent its attempts; answers already
  // pulled remain usable. None = all batches accounted for.
  def next: CIO[Option[Evaluation[AnswerSet]]]

  // idempotent abandon signal — workers consult it before issuing an
  // exchange, so abandoning the stream stops FURTHER exchanges, and an
  // in-flight exchange races it (aborted where the carrier can interrupt).
  def close: CIO[Unit]
}

private[typesafe4s] object BatchRun {

  // what a worker delivers to the channel: the batch's outcome, or a marker
  // when the worker yielded to abandonment before exchanging
  private type Slot = Try[Option[Evaluation[AnswerSet]]]

  // Validates+splits before forking: an unfittable question or an invalid
  // set fails THIS CIO before any worker exists — no exchange is made
  // (Scenario: One question that cannot ever fit).
  def start[A](
    config: TypesafeConfig,
    transport: Transport,
    clock: Clock,
    concurrency: ConcurrencyBound
  )(state: A, questions: Either[TypesafeException.InvalidQuestion, QuestionSet])(using enc: StateEncoder[A]): CIO[BatchRun] =
    questions match {
      case Left(e)   => CIO.fail(e)
      case Right(qs) =>
        TokenBudget.split(state, qs) match {
          case Left(e)        => CIO.fail(e)
          case Right(batches) => spawn(config, transport, clock, concurrency, state, batches)
        }
    }

  private def spawn[A](
    config: TypesafeConfig,
    transport: Transport,
    clock: Clock,
    concurrency: ConcurrencyBound,
    state: A,
    batches: List[typesafe4s.Batch]
  )(using enc: StateEncoder[A]): CIO[BatchRun] =
    for {
      // capacity = #batches: every worker delivers exactly once, so a `put`
      // can never suspend waiting on a consumer that stopped reading
      results   <- CChannel.init[Slot](batches.size)
      abandoned <- CAtomicBoolean.init(false)
      signal    <- CPromise.init[Unit]
      meter     <- CMeter.init(concurrency.value)
      remaining <- CAtomicInt.init(batches.size)
      _         <- CIO.foreachDiscard(batches) { batch =>
                     CFiber.init(worker(config, transport, clock, state, batch, meter, abandoned, signal, results)).unit
                   }
    } yield new Session(remaining, results, abandoned, signal)

  // a named class, not an anonymous one: facades inline `lower(run.next)`
  // across compilation units, and a local def inside an anonymous class
  // has no resolvable symbol at the inline site.
  final private class Session(
    remaining: CAtomicInt,
    results: CChannel[Slot],
    abandoned: CAtomicBoolean,
    signal: CPromise[Unit]
  ) extends BatchRun {

    def next: CIO[Option[Evaluation[AnswerSet]]] = pull

    private def pull: CIO[Option[Evaluation[AnswerSet]]] =
      remaining.get.flatMap { n =>
        if (n <= 0) CIO.value(None)
        else
          results.take.flatMap {
            case Success(Some(e)) => remaining.decrementAndGet.map(_ => Some(e))
            case Success(None)    => remaining.decrementAndGet.flatMap(_ => pull)
            case Failure(t)       => remaining.decrementAndGet.flatMap(_ => CIO.fail(t))
          }
      }

    def close: CIO[Unit] =
      abandoned.set(true).flatMap(_ => signal.succeed(()).unit)
  }

  // one batch's lifecycle: take a permit; yield to abandonment before the
  // exchange; race the exchange against the abandon latch — whichever
  // completes first wins, the loser is abandoned where the carrier can.
  private def worker[A](
    config: TypesafeConfig,
    transport: Transport,
    clock: Clock,
    state: A,
    batch: typesafe4s.Batch,
    meter: CMeter,
    abandoned: CAtomicBoolean,
    signal: CPromise[Unit],
    results: CChannel[Slot]
  )(using enc: StateEncoder[A]): CIO[Unit] =
    meter.run {
      abandoned.get.flatMap { gone =>
        if (gone) results.put(Success(None))
        else
          CIO
            .race(
              Ask.run(config, transport, clock)(state, batch.questions).liftToTry.map(t => t.map(Some(_))),
              signal.get.map(_ => Success(Option.empty[Evaluation[AnswerSet]]))
            )
            .flatMap(results.put)
      }
    }
}
