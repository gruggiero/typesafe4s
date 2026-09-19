package typesafe4s

import kyo.compat.CIO
import zio.{IO, Scope, ZIO}
import zio.stream.ZStream

import typesafe4s.client.{ApiSurface, BatchedClient, Client, LoweredClient, Transport, TypesafeConfig}

// ============================================================================
// spec: effect-portability — the ZIO facade. The ZIO row is the ecosystem
// with a typed failure channel: the alias names `IO[TypesafeException, A]`
// — the SDK's closed failure family — via `refineToOrDie` in `lower`
// (Requirement: Failures reach ZIO callers in a named channel). A failure
// outside the family is a defect, not a typed error.
//
// The facade supplies exactly two conversions; every operation is derived
// in `LoweredClient` from the shared carrier client.
// ============================================================================
// spec: question-batching — the published client is the shared batched
// surface instantiated with this row's stream type.
type TypesafeClient = BatchedClient[IO[TypesafeException, *], [A] =>> ZStream[Any, TypesafeException, A]]

object TypesafeClient extends ApiSurface[IO[TypesafeException, *]] {

  def of(config: TypesafeConfig): TypesafeClient =
    of(config, Transport.default)

  def of(config: TypesafeConfig, transport: Transport): TypesafeClient =
    new LoweredClient[IO[TypesafeException, *]](Client.cio(config, transport))
      with BatchedClient[IO[TypesafeException, *], [A] =>> ZStream[Any, TypesafeException, A]] {
      protected def lower[A](carried: CIO[A]): IO[TypesafeException, A] = TypesafeClient.lower(carried)
      protected def lift[A](fa: IO[TypesafeException, A]): CIO[A]       = TypesafeClient.lift(fa)

      def batched[A](
        state: A,
        questions: Either[TypesafeException.InvalidQuestion, QuestionSet],
        concurrency: ConcurrencyBound
      )(using enc: StateEncoder[A]): ZStream[Any, TypesafeException, Evaluation[AnswerSet]] =
        ZStream.unwrap {
          lower(startBatched(state, questions, concurrency)).map { run =>
            ZStream
              .unfoldZIO(run)(r => lower(r.next).map(_.map(e => (e, r))))
              .ensuring(lower(run.close).orDie)
          }
        }
    }

  // spec: client-configuration — the ZIO idiom: a client held by `Scope`,
  // released when the scope ends — success or failure
  // (`ZIO.acquireRelease`; a close failure is a defect, not a typed error).
  def scoped(config: TypesafeConfig): ZIO[Scope, TypesafeException, TypesafeClient] =
    scoped(config, Transport.default)

  def scoped(config: TypesafeConfig, transport: Transport): ZIO[Scope, TypesafeException, TypesafeClient] =
    ZIO.acquireRelease(ZIO.succeed(of(config, transport)))(client => client.close.orDie)

  // the two conversions this ecosystem supplies — nothing else is needed
  private[typesafe4s] def lower[A](carried: CIO[A]): IO[TypesafeException, A] =
    carried.lower.refineToOrDie[TypesafeException]

  private[typesafe4s] def lift[A](fa: IO[TypesafeException, A]): CIO[A] =
    CIO.lift(fa)
}
