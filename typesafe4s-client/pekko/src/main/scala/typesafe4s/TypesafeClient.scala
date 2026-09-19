package typesafe4s

import scala.concurrent.{ExecutionContext, Future}

import kyo.compat.CIO
import kyo.compat.internal.LocalCtx
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import typesafe4s.client.{ApiSurface, BatchedClient, Client, LoweredClient, Transport, TypesafeConfig}
import typesafe4s.client.internal.BatchRun

// ============================================================================
// spec: effect-portability — the Pekko facade. Pekko rides on
// `scala.concurrent.Future` (kyo-compat-future binding) plus Pekko Streams
// — the declared divergence: a Future carrier cannot abandon in-flight
// work (Ring 5 table).
//
// The facade supplies exactly two conversions; every operation is derived
// in `LoweredClient` from the shared carrier client.
// ============================================================================
// spec: question-batching — the published client is the shared batched
// surface instantiated with this row's stream type. DECLARED DIVERGENCE
// (Ring-5 table): abandoning the Source cannot preempt exchanges already
// issued on the Future carrier.
type TypesafeClient = BatchedClient[Future, [A] =>> Source[A, NotUsed]]

object TypesafeClient extends ApiSurface[Future] {

  def of(config: TypesafeConfig): TypesafeClient =
    of(config, Transport.default)

  def of(config: TypesafeConfig, transport: Transport): TypesafeClient =
    new LoweredClient[Future](Client.cio(config, transport)) with BatchedClient[Future, [A] =>> Source[A, NotUsed]] {
      protected def lower[A](carried: CIO[A]): Future[A] = TypesafeClient.lower(carried)
      protected def lift[A](fa: Future[A]): CIO[A]       = TypesafeClient.lift(fa)

      def batched[A](
        state: A,
        questions: Either[TypesafeException.InvalidQuestion, QuestionSet],
        concurrency: ConcurrencyBound
      )(using enc: StateEncoder[A]): Source[Evaluation[AnswerSet], NotUsed] = {
        // `lazyFutureSource` + `unfoldAsync` + an OUTER `watchTermination`
        // is the session triple: the future acquires, the step pulls
        // `next`, and stream termination — completion, failure, or
        // downstream cancel — closes the session. `unfoldResourceAsync`
        // would express the same shape but leaves a stage actor that
        // prevents `ActorSystem` termination after the stream ends
        // (observed on pekko 1.7.0).
        //
        // A cancel that lands while the acquire future is still pending
        // can beat the inner source into existence, so acquired sessions
        // are queued and re-checked on both sides of the flag: the
        // terminator drains what it finds, and an acquire completing
        // after the flag drains what IT finds (close is idempotent).
        // The declared divergence: a cancel signals the session but
        // cannot preempt an exchange already issued on the Future
        // carrier.
        val sessions  = new java.util.concurrent.ConcurrentLinkedQueue[BatchRun]()
        val cancelled = new java.util.concurrent.atomic.AtomicBoolean(false)

        def closeAll(): Unit = {
          var run = sessions.poll()
          while (run != null) {
            lower(run.close)
            run = sessions.poll()
          }
          ()
        }

        Source
          .lazyFutureSource { () =>
            lower(startBatched(state, questions, concurrency)).map { run =>
              sessions.add(run)
              if (cancelled.get()) closeAll()
              Source.unfoldAsync(())(_ => lower(run.next).map(_.map(e => ((), e)))(using ExecutionContext.parasitic))
            }(using ExecutionContext.parasitic)
          }
          .watchTermination() { (_, terminated) =>
            terminated.onComplete { _ =>
              cancelled.set(true)
              closeAll()
            }(using ExecutionContext.parasitic)
            NotUsed
          }
          .mapMaterializedValue(_ => NotUsed)
      }
    }

  // spec: client-configuration — `Future` has no scope idiom: `use` is the
  // bracket a Future carrier can offer — `body` runs with the client, then
  // `client.close` runs on EVERY outcome before `body`'s result completes
  // the returned Future. A close failure never masks `body`'s failure.
  def use[A](config: TypesafeConfig)(
    body: TypesafeClient => Future[A]
  )(using ExecutionContext): Future[A] =
    use(config, Transport.default)(body)

  def use[A](config: TypesafeConfig, transport: Transport)(
    body: TypesafeClient => Future[A]
  )(using ExecutionContext): Future[A] = {
    val client  = of(config, transport)
    val settled =
      try body(client)
      catch {
        case scala.util.control.NonFatal(e) => Future.failed(e)
      } // danger-scan:allow synchronous throw is reified into the Future, not swallowed
    settled.transformWith {
      case scala.util.Success(a) => client.close.map(_ => a)
      case scala.util.Failure(e) => client.close.transform(_ => scala.util.Failure(e))
    }
  }

  // the two conversions this ecosystem supplies — nothing else is needed.
  // `lower` materialises the carrier's `LocalCtx ?=> Future[A]` against
  // the root context — the same ctx `CIO.unsafeRun` uses.
  private[typesafe4s] def lower[A](carried: CIO[A]): Future[A] =
    carried.lower(using LocalCtx.empty)

  private[typesafe4s] def lift[A](fa: Future[A]): CIO[A] =
    CIO.lift(fa)
}
