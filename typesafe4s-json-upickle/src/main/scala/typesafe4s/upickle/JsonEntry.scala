package typesafe4s.upickle

import typesafe4s.{Entry, StateEncoder}
import typesafe4s.json.Json

// ============================================================================
// spec: json-adapters — validated wrapper + total given (mechanism B).
// `of` is the ONLY constructor: it crosses ujson.Value to the core AST and
// runs the checked Entry.fromJson, so a JsonEntry can only ever hold a value
// the API can carry. The given is total because the invariant was discharged
// at construction — the same pattern Entry itself uses.
// ============================================================================
opaque type JsonEntry = Entry

object JsonEntry {

  // Left names the dotted path of the first member that is not entry-shaped.
  def of(value: ujson.Value): Either[String, JsonEntry] =
    Entry.fromJson(toCore(value))

  // structural crossing — member order is kept (Obj.obj is an insertion-
  // ordered LinkedHashMap into a Vector)
  private def toCore(value: ujson.Value): Json = value match {
    case s: ujson.Str => Json.JString(s.str)
    case o: ujson.Obj => Json.JObject(o.obj.iterator.map { case (k, v) => k -> toCore(v) }.toVector)
    case a: ujson.Arr => Json.JArray(a.arr.iterator.map(toCore).toVector)
    // a non-finite Double has no BigDecimal; the payload is never observed —
    // fromJson refuses numbers regardless
    case n: ujson.Num => Json.JNumber(if (n.num.isFinite) BigDecimal(n.num) else BigDecimal(0))
    case ujson.True   => Json.JBool(true)
    case ujson.False  => Json.JBool(false)
    case ujson.Null   => Json.JNull
  }

  given StateEncoder[JsonEntry] = new StateEncoder[JsonEntry] {
    def encode(value: JsonEntry): Entry = value
  }

  extension (e: JsonEntry) def entry: Entry = e
}
