package typesafe4s.typecontract

import scala.concurrent.duration.FiniteDuration

import typesafe4s.{RequestId, TypesafeException}

// ============================================================================
// TYPED CONTRACT — spec: error-model (change: add-typesafe4s-sdk, spec 1/8)
//
// Gate 1 artifact, approved 2026-09-17. The declarations were promoted to
// typesafe4s-core/src/main/scala/typesafe4s/TypesafeException.scala at Step 3;
// this file remains as a living compile-time assertion of the approved
// surface — if the implementation drifts from it, this file stops compiling.
//
// Approved surface (Gate 1, corrected to sealed trait — see below):
//   opaque type RequestId = String
//   RequestId.fromHeader(value: String): Option[RequestId]
//   RequestId.value — extension (id: RequestId): String
//   sealed trait TypesafeException extends Exception
//     def requestId: Option[RequestId]
//     protected def describe: String
//     override final def getMessage: String
//   object TypesafeException — 13 members, all
//     `final case class … private[typesafe4s]`, plus
//     private[typesafe4s] def fromResponse(Int, List[(String,String)], String)
//
// Post-Gate-1 correction (re-approved at Gate 2): the family is a sealed TRAIT
// extending Exception, not a sealed abstract class — the abstract class's
// constructor-args shape leaves a legal anonymous-subclass hole in its own
// file, so "omitting a member is reported" would never fire.
// ============================================================================

private object ErrorModelTypeContract {

  // surface witnesses — each would stop compiling if the promoted surface drifted
  val fromHeaderSig: String => Option[RequestId]                                                       = RequestId.fromHeader
  val fromResponseSig: (Int, List[(String, String)], String) => TypesafeException                      =
    TypesafeException.fromResponse
  val rateLimitSig: (Option[RequestId], Option[FiniteDuration], String) => TypesafeException.RateLimit =
    TypesafeException.RateLimit.apply
  val traceAccessor: TypesafeException => Option[RequestId]                                            = _.requestId
  val ordinaryException: TypesafeException => Exception                                                = identity

  // spec: error-model — Scenario: Distinguishing every member suffices
  // exhaustive over all members, no catch-all — the closed family in code.
  // spec: question-model added InvalidQuestion (the 14th member, 2026-09-18):
  // a limit-breaking question or set refused at submission, before any request
  // spec: client-configuration added InvalidConfiguration (the 15th member):
  // an unusable environment value fails configuration resolution, naming the
  // variable
  def memberName(e: TypesafeException): String = e match {
    case _: TypesafeException.BadRequest           => "BadRequest"
    case _: TypesafeException.Authentication       => "Authentication"
    case _: TypesafeException.PermissionDenied     => "PermissionDenied"
    case _: TypesafeException.NotFound             => "NotFound"
    case _: TypesafeException.UnprocessableEntity  => "UnprocessableEntity"
    case _: TypesafeException.RateLimit            => "RateLimit"
    case _: TypesafeException.Overloaded           => "Overloaded"
    case _: TypesafeException.InternalServer       => "InternalServer"
    case _: TypesafeException.ResponseValidation   => "ResponseValidation"
    case _: TypesafeException.ConnectionFailed     => "ConnectionFailed"
    case _: TypesafeException.Timeout              => "Timeout"
    case _: TypesafeException.MissingAnswer        => "MissingAnswer"
    case _: TypesafeException.MissingCredential    => "MissingCredential"
    case _: TypesafeException.InvalidQuestion      => "InvalidQuestion"
    case _: TypesafeException.InvalidConfiguration => "InvalidConfiguration"
  }
}

// ============================================================================
// Property obligations (Ring 2) — discharged by typesafe4s.ErrorModelProperties
// ----------------------------------------------------------------------------
// P1 `every-documented-refusal-maps-to-one-member` — genRefusal (constructive,
//    8 documented classes + generated body/headers); totality + injectivity.
// P2 `trace-is-carried-or-absent` — refusal side; genOutcome draws the
//    x-typesafe-request-id header present / absent / present-but-empty by
//    construction. Success row deferred to question-model's AnswerSet (Gate-0).
// P3 `unreachable-is-not-timeout` — ConnectionFailed / Timeout are distinct
//    members; neither carries a requestId.
//
// Compile-negative obligations — outside/ErrorModelCompileNegativeSuite.scala
// (foreign package so `private[typesafe4s]` construction is genuinely denied):
//   * omitting a member from a match is reported before the program runs
//   * constructing a family member from outside the SDK does not compile
//   * NOTE: a compileErrors snippet that EXTENDS the sealed family registers
//     the fake child in the run's sealed-children cache and poisons every
//     other exhaustivity check in the compilation — deliberately absent.
//
// Deferred rows (Gate-0 decisions, 2026-09-17):
//   * success-side trace -> spec 3 (AnswerSet carries Option[RequestId])
//   * MissingAnswer no-request row -> spec 3; MissingCredential construction
//     row -> spec 7
// ============================================================================
