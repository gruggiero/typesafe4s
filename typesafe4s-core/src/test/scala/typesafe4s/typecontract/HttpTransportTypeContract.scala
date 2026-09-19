package typesafe4s.typecontract

import typesafe4s.{ApiKey, HttpMethod, HttpRequest, HttpResponse}

// ============================================================================
// TYPED CONTRACT — spec: http-transport (change: add-typesafe4s-sdk, spec 5/8)
//
// Gate 1 artifact, APPROVED 2026-09-18. The declarations were promoted to
// typesafe4s-core/src/main/scala/typesafe4s/{ApiKey,HttpMethod,HttpRequest,
// HttpResponse}.scala at Step 3; this file remains as a living compile-time
// assertion of the approved surface — if the implementation drifts from it,
// this file stops compiling.
//
// Approved surface (Gate 1):
//   final class ApiKey private (private val value: String) extends AnyVal —
//     redacting toString; object ApiKey — of(String): ApiKey (non-blank);
//     extension (k: ApiKey) def authorization: (String, String)   [present]
//   enum HttpMethod — Get | Post
//   final case class HttpRequest private (method, target, headers, body) —
//     private ctor: HttpRequest.exchange is the only construction path
//   object HttpRequest — exchange(method, target, body, key, configured,
//     perCall): HttpRequest — authorization is a REQUIRED named parameter
//   final case class HttpResponse (status, headers, body) — public apply:
//     transports (incl. caller-supplied ones) must be able to build one
//
// Approved design decisions:
//   T1  ApiKey is a real (value) class, NOT an opaque type: `opaque type
//       ApiKey = String` cannot redact — `key.toString` would compile to
//       String.toString and print the secret. A private-ctor class with a
//       private field and a redacting toString leaves no string-form path
//       that yields the secret (Credential: "render is total"). Spec 7 owns
//       the redaction VERIFICATION; the type lands here already shaped.
//   T2  `authorization` (Credential/present) produces the header pair
//       ("Authorization", s"Bearer <key>") — scheme confirmed in
//       concepts/credential.md value domains.
//   T3  `ApiKey.of` refuses blank — presence, not shape: the key format is
//       MUST-CONFIRM so nothing about its structure is checked; a blank
//       Bearer header is not a key the server could ever accept.
//   T4  HttpRequest's ctor is private: `exchange` is the only construction
//       path and it REQUIRES `key: ApiKey` — the compile-negative "sending a
//       request with no authorization" is discharged by the missing named
//       parameter, not by review. Taking the key (not a bare header string)
//       means the sent authorization is definitionally what present produced.
//   T5  Merge rule for headers: configured is overridden by perCall for every
//       name — EXCEPT the two the SDK owns: "Authorization" always carries
//       the credential's value and "Content-Type" always declares the actual
//       body. A configured/per-call entry under those names is dropped, not
//       honoured — "an exchange carries what the service requires" is
//       mandatory, so caller headers must not be able to displace it. The
//       merged list holds each name once (case-insensitive), insertion order.
//   T6  Content-Type is sent only when a body is present — a bodiless GET has
//       no body to declare. The value is fixed "application/json" (the wire
//       codec is JSON; no other media type exists on the service surface).
//   T7  HttpMethod is a two-case enum, not a String: the service surface is
//       POST /v1/systemone and GET /v1/models (evaluation.md value domains);
//       nothing else can be asked for, so nothing else can be expressed.
//   T8  HttpResponse keeps a PUBLIC apply: the JdkHttpTransport, the stand-in
//       exchange and every caller-supplied Transport build responses. It is
//       data — no invariant to protect.
//   T9  Headers are List[(String, String)] — the shape
//       TypesafeException.fromResponse already consumes (spec 1); no second
//       header type is introduced.
// ============================================================================

private object HttpTransportTypeContract {

  // surface witnesses — each stops compiling if the promoted surface drifts.
  // The witnesses evaluate nothing at class-init (defs, not vals): a `???`
  // during the pre-promotion window must fail tests, not poison the object.
  def witnessExchange: HttpRequest                        =
    HttpRequest.exchange(HttpMethod.Post, "/v1/systemone", Some("{}"), ApiKey.of("k"))
  def witnessAuthorization(key: ApiKey): (String, String) = key.authorization
  def witnessResponse: HttpResponse                       = HttpResponse(200, Nil, "{}")
}
