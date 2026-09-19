package typesafe4s.circe

import typesafe4s.{Entry, StateEncoder}
import typesafe4s.json.Json

// ============================================================================
// spec: json-adapters — validated wrapper + total given (mechanism B).
// `of` is the ONLY constructor: it crosses circe's Json to the core AST and
// runs the checked Entry.fromJson, so a JsonEntry can only ever hold a value
// the API can carry. The given is total because the invariant was discharged
// at construction — the same pattern Entry itself uses.
// ============================================================================
opaque type JsonEntry = Entry

object JsonEntry {

  // Left names the dotted path of the first member that is not entry-shaped.
  def of(value: io.circe.Json): Either[String, JsonEntry] =
    Entry.fromJson(toCore(value))

  // structural crossing — member order is kept (JsonObject iterates in
  // insertion order into a Vector)
  private def toCore(value: io.circe.Json): Json = value.fold(
    Json.JNull,
    b => Json.JBool(b),
    // toBigDecimal is absent only for pathological BiggerDecimal scales; the
    // payload is never observed — fromJson refuses numbers regardless
    n => Json.JNumber(n.toBigDecimal.getOrElse(BigDecimal(0))),
    s => Json.JString(s),
    vs => Json.JArray(vs.map(toCore)),
    o => Json.JObject(o.toVector.map { case (k, v) => k -> toCore(v) })
  )

  given StateEncoder[JsonEntry] = new StateEncoder[JsonEntry] {
    def encode(value: JsonEntry): Entry = value
  }

  extension (e: JsonEntry) def entry: Entry = e
}
