package typesafe4s.playjson

import play.api.libs.json.{JsArray, JsFalse, JsNull, JsNumber, JsObject, JsString, JsTrue, JsValue}

import typesafe4s.{Entry, StateEncoder}
import typesafe4s.json.Json

// ============================================================================
// spec: json-adapters — validated wrapper + total given (mechanism B), for
// play-json (#32). `of` is the ONLY constructor: it crosses a JsValue to the
// core AST and runs the checked Entry.fromJson, so a JsonEntry can only ever
// hold a value the API can carry. The given is total because the invariant
// was discharged at construction — the same pattern Entry itself uses.
//
// The package is `playjson`, not `play`: a `typesafe4s.play` package would
// shadow the root `play` package for every file under `typesafe4s`.
// ============================================================================
opaque type JsonEntry = Entry

object JsonEntry {

  // Left names the dotted path of the first member that is not entry-shaped.
  def of(value: JsValue): Either[String, JsonEntry] =
    Entry.fromJson(toCore(value))

  // structural crossing — member order is kept (JsObject keeps insertion
  // order), and a JsNumber is already a BigDecimal, so nothing is rounded
  // even though fromJson refuses numbers regardless
  private def toCore(value: JsValue): Json = value match {
    case JsString(s) => Json.JString(s)
    case o: JsObject => Json.JObject(o.fields.iterator.map { case (k, v) => k -> toCore(v) }.toVector)
    case JsArray(vs) => Json.JArray(vs.iterator.map(toCore).toVector)
    case JsNumber(n) => Json.JNumber(n)
    // JsBoolean's extractor is not checkable for exhaustivity; its two cases are
    case JsTrue      => Json.JBool(true)
    case JsFalse     => Json.JBool(false)
    case JsNull      => Json.JNull
  }

  given StateEncoder[JsonEntry] = new StateEncoder[JsonEntry] {
    def encode(value: JsonEntry): Entry = value
  }

  extension (e: JsonEntry) def entry: Entry = e
}
