package typesafe4s

import cats.effect.{IO, Resource}
import fs2.Stream
import kyo.compat.CIO

import typesafe4s.client.{ApiSurface, BatchedClient, Client, LoweredClient, Transport, TypesafeConfig}

// ============================================================================
// spec: effect-portability — the Cats Effect facade. Failures ride IO's
// throwable channel — this ecosystem's native error model.
//
// The facade supplies exactly two conversions; every operation is derived
// in `LoweredClient` from the shared carrier client.
// ============================================================================
// spec: question-batching — the published client is the shared batched
// surface instantiated with this row's stream type.
type TypesafeClient = BatchedClient[IO, [A] =>> Stream[IO, A]]

object TypesafeClient extends ApiSurface[IO] {

  def of(config: TypesafeConfig): TypesafeClient =
    of(config, Transport.default)

  def of(config: TypesafeConfig, transport: Transport): TypesafeClient =
    new LoweredClient[IO](Client.cio(config, transport)) with BatchedClient[IO, [A] =>> Stream[IO, A]] {
      protected def lower[A](carried: CIO[A]): IO[A] = TypesafeClient.lower(carried)
      protected def lift[A](fa: IO[A]): CIO[A]       = TypesafeClient.lift(fa)

      def batched[A](
        state: A,
        questions: Either[TypesafeException.InvalidQuestion, QuestionSet],
        concurrency: ConcurrencyBound
      )(using enc: StateEncoder[A]): Stream[IO, Evaluation[AnswerSet]] =
        Stream
          .bracket(lower(startBatched(state, questions, concurrency)))(run => lower(run.close))
          .flatMap(run => Stream.unfoldEval(run)(r => lower(r.next).map(_.map(e => (e, r)))))
    }

  // spec: client-configuration — the Cats Effect idiom: a `Resource` whose
  // `use` runs the release on success, error and cancellation.
  def resource(config: TypesafeConfig): Resource[IO, TypesafeClient] =
    resource(config, Transport.default)

  def resource(config: TypesafeConfig, transport: Transport): Resource[IO, TypesafeClient] =
    Resource.make(IO.pure(of(config, transport)))(client => client.close)

  // the two conversions this ecosystem supplies — nothing else is needed
  private[typesafe4s] def lower[A](carried: CIO[A]): IO[A] = carried.lower
  private[typesafe4s] def lift[A](fa: IO[A]): CIO[A]       = CIO.lift(fa)
}
