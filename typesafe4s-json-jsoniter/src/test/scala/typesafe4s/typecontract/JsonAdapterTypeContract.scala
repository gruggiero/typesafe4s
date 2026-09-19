package typesafe4s.typecontract

import typesafe4s.{Entry, QuestionSet, StateEncoder, SystemOne}
import typesafe4s.jsoniter.JsonEntry

// ============================================================================
// TYPED CONTRACT — spec: json-adapters (change: add-typesafe4s-sdk, spec 9/10)
//
// Gate 1 artifact. Module: typesafe4s-jsoniter.
//
// Approved mechanism (user decision, option B): validated wrapper + total
// given. jsoniter-scala ships no public AST, so the adapter's input is
// encoded JSON — `Array[Byte]` or `String`, as produced by jsoniter's
// writers. `JsonEntry.of` parses it with the core parser and runs the
// checked `Entry.fromJson`: Left names the parse failure or the dotted path
// of the first non-entry-shaped member. The given is total because the
// invariant was discharged at construction — the same pattern Entry itself
// uses.
// ============================================================================

private object JsonAdapterTypeContract {

  // the checked crossing — encoded JSON is the only way in
  def witnessOfBytes(json: Array[Byte]): Either[String, JsonEntry] = JsonEntry.of(json)
  def witnessOfText(json: String): Either[String, JsonEntry]       = JsonEntry.of(json)

  // the total given — resolves for the wrapper
  val encoderSig: StateEncoder[JsonEntry] = summon[StateEncoder[JsonEntry]]

  def witnessEncode(e: JsonEntry): Entry = summon[StateEncoder[JsonEntry]].encode(e)

  // a JsonEntry is usable where evaluation state is asked
  def witnessState(e: JsonEntry, qs: QuestionSet) =
    SystemOne.renderRequest(e, "jev-latest", qs)

  // the wrapper opens back to the Entry it validated
  def witnessEntry(e: JsonEntry): Entry = e.entry
}

// ============================================================================
// Property obligations (Ring 2) — adapter conversion suite:
//   `conversion-is-faithful-order-included` — entry-shaped JSON text converts
//      to an Entry rendering identically (members, order, nesting)
//   `a-refusal-names-a-real-violation` — planted number/boolean → Left naming
//      the position; non-JSON input → Left naming the parse failure
//   `nothing-stays-nothing` — `null` converts to Entry.nothing
//
// Compile-Negative Obligation — outside/JsonAdapterCompileNegativeSuite:
//   * `summon[StateEncoder[Array[Byte]]]` must NOT resolve — encoded JSON is
//     not implicitly usable as state; the checked `of` is the only way in
// ============================================================================
