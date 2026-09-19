package typesafe4s

import kyo.compat.CIO
import ox.Ox
import ox.flow.Flow

import typesafe4s.client.{ApiSurface, BatchedClient, Client, LoweredClient, Transport, TypesafeConfig}

// ============================================================================
// spec: effect-portability — the Ox facade. The Ox effect is context-passed:
// `F[A] = Ox ?=> A` — a `supervised` scope must be in scope at the caller's
// use site. Failures surface as thrown exceptions — Ox's native error model.
//
// The facade supplies exactly two conversions; every operation is derived
// in `LoweredClient` from the shared carrier client.
// ============================================================================
// spec: question-batching — the published client is the shared batched
// surface instantiated with this row's stream type.
type TypesafeClient = BatchedClient[[A] =>> Ox ?=> A, [A] =>> Ox ?=> Flow[A]]

object TypesafeClient extends ApiSurface[[A] =>> Ox ?=> A] {

  def of(config: TypesafeConfig): TypesafeClient =
    of(config, Transport.default)

  def of(config: TypesafeConfig, transport: Transport): TypesafeClient =
    new LoweredClient[[A] =>> Ox ?=> A](Client.cio(config, transport)) with BatchedClient[[A] =>> Ox ?=> A, [A] =>> Ox ?=> Flow[A]] {
      protected def lower[A](carried: CIO[A]): Ox ?=> A = TypesafeClient.lower(carried)
      protected def lift[A](fa: Ox ?=> A): CIO[A]       = TypesafeClient.lift(fa)

      def batched[A](
        state: A,
        questions: Either[TypesafeException.InvalidQuestion, QuestionSet],
        concurrency: ConcurrencyBound
      )(using enc: StateEncoder[A]): Ox ?=> Flow[Evaluation[AnswerSet]] =
        // `usingEmit`'s block is the flow's run loop; when the consumer
        // stops early the emit channel closes, `emit` throws, and the
        // finally still reaches `run.close` — abandonment on this row
        // ends the session rather than leaking bounded workers.
        Flow.usingEmit { emit =>
          val run = lower(startBatched(state, questions, concurrency))
          try {
            var done = false
            while (!done)
              lower(run.next) match {
                case Some(e) => emit(e)
                case None    => done = true
              }
          } finally
            lower(run.close)
        }
    }

  // spec: client-configuration — the Ox idiom: the client is acquired
  // inside the caller's `supervised` scope; a scope finalizer
  // (`Ox.addFinalizer`, via `ResourceScope`) runs the release when the
  // scope ends — success or failure.
  def scoped(config: TypesafeConfig)(using Ox): TypesafeClient =
    scoped(config, Transport.default)

  def scoped(config: TypesafeConfig, transport: Transport)(using scope: Ox): TypesafeClient =
    ox.useInScope(of(config, transport))(client => client.close(using scope))

  // the two conversions this ecosystem supplies — nothing else is needed
  private[typesafe4s] def lower[A](carried: CIO[A]): Ox ?=> A = carried.lower
  private[typesafe4s] def lift[A](fa: Ox ?=> A): CIO[A]       = CIO.lift(fa)
}
