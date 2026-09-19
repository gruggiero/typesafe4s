package typesafe4s

import typesafe4s.json.Json

// ============================================================================
// spec: wire-codec — Concepts Introduced: Entry
// Restricted Json — the shapes the API accepts for instructions and
// descriptions: text, object, array, nothing. The restriction is recursive:
// obj/arr children are themselves Entry, so a number or a boolean is
// unrepresentable anywhere in an Entry, not just at top level.
// ============================================================================
opaque type Entry = Json

object Entry {

  def text(value: String): Entry = Json.JString(value)

  // members keep the order they were given
  def obj(members: (String, Entry)*): Entry = Json.JObject(members.toVector)

  def arr(values: Entry*): Entry = Json.JArray(values.toVector)

  val nothing: Entry = Json.JNull

  // spec: wire-codec — the checked conversion. Right when every member of the
  // tree is entry-shaped; Left names the dotted path of the first member that
  // is not. The ONLY way raw Json becomes an Entry — there is no given.
  def fromJson(json: Json): Either[String, Entry] =
    firstViolation("$", json).toLeft(json)

  // the dotted path of the first member that is not entry-shaped, if any
  private def firstViolation(path: String, json: Json): Option[String] = json match {
    case Json.JNull | _: Json.JString    => None
    case _: Json.JBool | _: Json.JNumber => Some(path)
    case Json.JArray(values)             =>
      values.view.zipWithIndex.flatMap { case (value, i) => firstViolation(s"$path.$i", value) }.headOption
    case Json.JObject(members)           =>
      members.view.flatMap { case (name, value) => firstViolation(s"$path.$name", value) }.headOption
  }

  extension (e: Entry) {
    def json: Json = e
  }
}
