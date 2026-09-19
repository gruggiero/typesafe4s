package typesafe4s

// spec: http-transport — Concepts Introduced: HttpResponse — what an
// exchange produced: status, the headers the service sent, the body read.
// PUBLIC apply: the JdkHttpTransport, the stand-in exchange and every
// caller-supplied Transport build responses — it is data, with no
// invariant to protect. Headers keep the shape
// TypesafeException.fromResponse already consumes (spec 1).
final case class HttpResponse(
  status: Int,
  headers: List[(String, String)],
  body: String
)
