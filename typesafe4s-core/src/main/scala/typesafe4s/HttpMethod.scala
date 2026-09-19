package typesafe4s

// spec: http-transport — Concepts Introduced: HttpRequest (part of). A
// two-case enum, not a String: the service surface is POST /v1/systemone
// and GET /v1/models (concepts/evaluation.md value domains); nothing else
// can be asked for, so nothing else can be expressed.
enum HttpMethod {
  case Get, Post
}
