package typesafe4s.ziojson

import zio.json.ast.{Json => ZJson}

import typesafe4s.{Entry, StateEncoder}
import typesafe4s.json.Json

// ============================================================================
// spec: json-adapters — validated wrapper + total given (mechanism B).
// `of` is the ONLY constructor: it crosses zio-json's ast.Json to the core AST
// and runs the checked Entry.fromJson, so a JsonEntry can only ever hold a
// value the API can carry. The given is total because the invariant was
// discharged at construction — the same pattern Entry itself uses.
// ============================================================================
opaque type JsonEntry = Entry

object JsonEntry {

  // Left names the dotted path of the first member that is not entry-shaped.
  def of(value: ZJson): Either[String, JsonEntry] =
    Entry.fromJson(toCore(value))

  // structural crossing — member order is kept (Obj.fields is an ordered
  // Chunk of pairs into a Vector)
  private def toCore(value: ZJson): Json = value match {
    case ZJson.Obj(fields) => Json.JObject(fields.map { case (k, v) => k -> toCore(v) }.toVector)
    case ZJson.Arr(vs)     => Json.JArray(vs.map(toCore).toVector)
    case ZJson.Bool(b)     => Json.JBool(b)
    case ZJson.Num(n)      => Json.JNumber(BigDecimal(n))
    case ZJson.Str(s)      => Json.JString(s)
    case ZJson.Null        => Json.JNull
  }

  given StateEncoder[JsonEntry] = new StateEncoder[JsonEntry] {
    def encode(value: JsonEntry): Entry = value
  }

  extension (e: JsonEntry) def entry: Entry = e
}
