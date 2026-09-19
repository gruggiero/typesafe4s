package typesafe4s

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration

// spec: error-model — Concepts Introduced: RequestId
// The trace the service returns; the only handle support has. Reported absent
// — never blank, never invented — when the service sent none.
opaque type RequestId = String

object RequestId {

  /**
    * Present only when the service sent a non-blank trace value. A blank header is reported as absent.
    */
  def fromHeader(value: String): Option[RequestId] =
    if (value.isBlank) None else Some(value)

  extension (id: RequestId) {
    def value: String = id
  }
}

// spec: error-model — Concepts Introduced: TypesafeException
// Closed family: every failure of Evaluation/ask, matchable exhaustively.
// Sealed TRAIT — an abstract class would leave a legal anonymous-subclass hole
// in this file and the "omitting a member is reported" guarantee would not
// fire. Members are only constructible inside the SDK (`private[typesafe4s]`)
// so a caller cannot fabricate a service-reported failure — the sole exception
// is ConnectionFailed, whose public ctor (spec http-transport) lets a
// caller-supplied Transport report its own exchange faults; it carries no
// requestId or service trace, so nothing service-reported can be fabricated.
sealed trait TypesafeException extends Exception {

  /**
    * The service's own trace. Members that never reach the service have no requestId field at all — absence is
    * type-level, not a convention.
    */
  def requestId: Option[RequestId] = None

  /**
    * What went wrong, in readable terms.
    */
  protected def describe: String

  final override def getMessage: String =
    describe + requestId.fold("")(id => s" [request id: ${RequestId.value(id)}]")
}

object TypesafeException {

  // spec: error-model — "a refusal the caller caused" (400)
  final case class BadRequest private[typesafe4s] (override val requestId: Option[RequestId], detail: String) extends TypesafeException {
    protected def describe: String = s"the service refused the request as malformed: $detail"
  }

  // spec: error-model — "a refusal the caller caused" (401)
  final case class Authentication private[typesafe4s] (override val requestId: Option[RequestId], detail: String) extends TypesafeException {
    protected def describe: String = s"the service refused the request as unauthenticated: $detail"
  }

  // spec: error-model — "a refusal the caller caused" (403)
  final case class PermissionDenied private[typesafe4s] (override val requestId: Option[RequestId], detail: String) extends TypesafeException {
    protected def describe: String = s"the service refused the request as forbidden: $detail"
  }

  // spec: error-model — "a refusal the caller caused" (404)
  final case class NotFound private[typesafe4s] (override val requestId: Option[RequestId], detail: String) extends TypesafeException {
    protected def describe: String = s"the service reported the subject as unknown: $detail"
  }

  // spec: error-model — "a refusal the caller caused" (422)
  final case class UnprocessableEntity private[typesafe4s] (override val requestId: Option[RequestId], detail: String) extends TypesafeException {
    protected def describe: String = s"the service refused the request as unprocessable: $detail"
  }

  // spec: error-model — "too many requests" (429); retryAfter carries the delay
  // the service named, when it named one (Retry-After / retry-after-ms headers,
  // header names MUST-CONFIRM per registry — task 12.3)
  final case class RateLimit private[typesafe4s] (
    override val requestId: Option[RequestId],
    retryAfter: Option[FiniteDuration],
    detail: String
  ) extends TypesafeException {
    protected def describe: String = s"the service rate-limited the request: $detail"
  }

  // spec: error-model — "overload is told apart from other service faults" (529);
  // deliberate superset of the reference SDK's hierarchy — MUST-CONFIRM 529
  // semantics against the service (task 12.3)
  final case class Overloaded private[typesafe4s] (override val requestId: Option[RequestId], detail: String) extends TypesafeException {
    protected def describe: String = s"the service reported itself overloaded: $detail"
  }

  // spec: error-model — "a general service fault": every other refusal carries
  // the status and what the service reported (includes 408 and 5xx minus 529)
  final case class InternalServer private[typesafe4s] (
    status: Int,
    override val requestId: Option[RequestId],
    detail: String
  ) extends TypesafeException {
    protected def describe: String = s"the service failed (status $status): $detail"
  }

  // spec: error-model — a 2xx answer that did not fit the agreed shape; reached
  // the service, so it carries the trace and a dotted field path (task 4.4)
  final case class ResponseValidation private[typesafe4s] (
    override val requestId: Option[RequestId],
    path: String,
    detail: String
  ) extends TypesafeException {
    protected def describe: String = s"the service's answer did not fit the agreed shape at '$path': $detail"
  }

  // spec: error-model — "the service cannot be reached"; carries the underlying cause.
  // PUBLIC constructor, unlike the service-reported members: it holds no
  // requestId or service trace, so a caller-supplied Transport can (and
  // should — spec http-transport T10) raise it for its own exchange faults
  // without fabricating anything the service said.
  final case class ConnectionFailed(cause: Throwable) extends TypesafeException {
    override def getCause: Throwable = cause
    protected def describe: String   = s"no connection to the service could be established: ${cause.getMessage}"
  }

  // spec: error-model — "an attempt runs out of time"; names the limit that
  // elapsed and is not reported as unreachable
  final case class Timeout private[typesafe4s] (limit: FiniteDuration) extends TypesafeException {
    protected def describe: String = s"the attempt ran out of time after ${limit.toMillis} ms"
  }

  // spec: error-model — "reading an answer that was never asked"; names the
  // name read. Produced by AnswerSet's lookup (question-model spec)
  final case class MissingAnswer private[typesafe4s] (name: String) extends TypesafeException {
    protected def describe: String = s"no answer was asked for the name '$name'"
  }

  // spec: question-model — "a limit broken only at run time still fails
  // safely": a question or question set whose shape breaks a documented limit,
  // detected at submission, is refused before any request is made. `name`
  // names the offending question; absent for a set-level failure (an empty
  // question set has no question to name).
  final case class InvalidQuestion private[typesafe4s] (name: Option[String], detail: String) extends TypesafeException {
    protected def describe: String =
      name.fold(s"the question set cannot be asked: $detail")(n => s"the question '$n' cannot be asked: $detail")
  }

  // spec: error-model — "no credential can be resolved"; names the environment
  // variable to set. Raised by client construction (client-configuration spec).
  // Member added to the anchor list by Gate-0 approval, 2026-09-17
  final case class MissingCredential private[typesafe4s] (envVar: String) extends TypesafeException {
    protected def describe: String = s"no credential could be resolved; set $envVar"
  }

  // spec: client-configuration — "an environment value cannot be used";
  // names the variable. Raised by configuration resolution — e.g. an
  // unparseable TYPESAFE_LOG_LEVEL fails construction rather than silently
  // defaulting (Gate-1 amendment A2).
  final case class InvalidConfiguration private[typesafe4s] (variable: String, detail: String) extends TypesafeException {
    protected def describe: String = s"the configured value of $variable cannot be used: $detail"
  }

  /**
    * Maps a service refusal to its family member. Total over the documented refusals (400, 401, 403, 404, 422, 429,
    * 529, general service faults); any other non-2xx status lands in `InternalServer` carrying the status.
    *
    * Reads `x-typesafe-request-id` and the retry-after headers from `headers` — header lookup is case-insensitive.
    */
  private[typesafe4s] def fromResponse(status: Int, headers: List[(String, String)], body: String): TypesafeException = {
    // explicit rejection, not a mapping: a status below 400 is not a refusal
    // and must never become a family member — callers pass refusal statuses only
    require(status >= 400, s"fromResponse maps service refusals (status >= 400), got $status")
    val requestId = header(headers, "x-typesafe-request-id").flatMap(RequestId.fromHeader)
    status match {
      case 400 => BadRequest(requestId, body)
      case 401 => Authentication(requestId, body)
      case 403 => PermissionDenied(requestId, body)
      case 404 => NotFound(requestId, body)
      case 422 => UnprocessableEntity(requestId, body)
      case 429 => RateLimit(requestId, retryAfter(headers), body)
      case 529 => Overloaded(requestId, body)
      // every other refusal is a general service fault carrying what the service reported
      case s   => InternalServer(s, requestId, body)
    }
  }

  private def header(headers: List[(String, String)], name: String): Option[String] =
    headers.collectFirst { case (n, v) if n.equalsIgnoreCase(name) => v }

  // the service names its delay in Retry-After (seconds, standard) or
  // retry-after-ms (millis); a missing, blank or unparseable value means it
  // named none — header names MUST-CONFIRM per registry (task 12.3)
  private def retryAfter(headers: List[(String, String)]): Option[FiniteDuration] = {
    val seconds = header(headers, "Retry-After").flatMap(parseDelay(_, TimeUnit.SECONDS))
    val millis  = header(headers, "retry-after-ms").flatMap(parseDelay(_, TimeUnit.MILLISECONDS))
    seconds.orElse(millis)
  }

  // FiniteDuration is bounded to ±(2^63-1)ns — a parseable-but-unrepresentable
  // or negative value is not a delay the service could have named, so it is
  // reported unnamed rather than throwing out of the family
  private def parseDelay(value: String, unit: TimeUnit): Option[FiniteDuration] =
    value.toLongOption.flatMap { n =>
      val maxInUnit = Long.MaxValue / unit.toNanos(1)
      Option.when(n >= 0 && n <= maxInUnit)(FiniteDuration(n, unit))
    }
}
