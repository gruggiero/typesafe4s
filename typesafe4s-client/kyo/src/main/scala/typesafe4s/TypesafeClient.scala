package typesafe4s

import kyo.{<, Abort, Async, Chunk, Emit, Scope, Stream, Sync}
import kyo.compat.CIO

import typesafe4s.client.{ApiSurface, BatchedClient, Client, LoweredClient, Transport, TypesafeConfig}

// ============================================================================
// spec: effect-portability — the Kyo facade. The Kyo effect carries its
// failure and async requirements in the pending set:
// `F[A] = A < (Abort[Throwable] & Async)`.
//
// The facade supplies exactly two conversions; every operation is derived
// in `LoweredClient` from the shared carrier client.
// ============================================================================
// spec: question-batching — the published client is the shared batched
// surface instantiated with this row's stream type.
// The stream carries `Scope` in its pending set: the session's release is
// registered with the enclosing `Scope.run`, so `close` fires however the
// stream ends — exhaustion, a failed batch, or an early `take`/abandon
// (Emit drops the suspended producer continuation, so a close sequenced
// in-line would never run on abandonment). Callers drain inside
// `Scope.run` — the Kyo-native shape for a resourceful stream.
type TypesafeClient =
  BatchedClient[[A] =>> A < (Abort[Throwable] & Async), [A] =>> Stream[A, Abort[Throwable] & Async & Scope]]

object TypesafeClient extends ApiSurface[[A] =>> A < (Abort[Throwable] & Async)] {

  def of(config: TypesafeConfig): TypesafeClient =
    of(config, Transport.default)

  def of(config: TypesafeConfig, transport: Transport): TypesafeClient =
    new LoweredClient[[A] =>> A < (Abort[Throwable] & Async)](Client.cio(config, transport))
      with BatchedClient[[A] =>> A < (Abort[Throwable] & Async), [A] =>> Stream[A, Abort[Throwable] & Async & Scope]] {
      protected def lower[A](carried: CIO[A]): A < (Abort[Throwable] & Async) = TypesafeClient.lower(carried)
      protected def lift[A](fa: A < (Abort[Throwable] & Async)): CIO[A]       = TypesafeClient.lift(fa)

      def batched[A](
        state: A,
        questions: Either[TypesafeException.InvalidQuestion, QuestionSet],
        concurrency: ConcurrencyBound
      )(using enc: StateEncoder[A]): Stream[Evaluation[AnswerSet], Abort[Throwable] & Async & Scope] =
        // `Scope.ensure` registers the session's close with the enclosing
        // scope — it fires on exhaustion, on a failed batch, and on early
        // termination (`take`/abandon), where an in-line close would be
        // discarded with the producer continuation.
        Stream {
          lower(startBatched(state, questions, concurrency)).map { run =>
            Scope.ensure(lower(run.close)).andThen {
              def step: Unit < (Emit[Chunk[Evaluation[AnswerSet]]] & Abort[Throwable] & Async) =
                lower(run.next).map {
                  case Some(e) => Emit.value(Chunk(e)).andThen(step)
                  case None    => ()
                }
              Abort.run[Throwable](step).map(outcome => Abort.get(outcome))
            }
          }
        }
    }

  // spec: client-configuration — the Kyo idiom: the `Scope` effect.
  // `Scope.acquireRelease` runs the release when the enclosing `Scope.run`
  // finishes — success or failure.
  def scoped(config: TypesafeConfig): TypesafeClient < (Scope & Sync) =
    scoped(config, Transport.default)

  def scoped(config: TypesafeConfig, transport: Transport): TypesafeClient < (Scope & Sync) =
    Scope.acquireRelease(of(config, transport))(client => client.close)

  // the two conversions this ecosystem supplies — nothing else is needed
  private[typesafe4s] def lower[A](carried: CIO[A]): A < (Abort[Throwable] & Async) = carried.lower
  private[typesafe4s] def lift[A](fa: A < (Abort[Throwable] & Async)): CIO[A]       = CIO.lift(fa)
}
