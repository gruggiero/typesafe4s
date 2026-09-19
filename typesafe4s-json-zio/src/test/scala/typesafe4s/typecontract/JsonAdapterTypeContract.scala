package typesafe4s.typecontract

import typesafe4s.{Entry, QuestionSet, StateEncoder, SystemOne}
import typesafe4s.ziojson.JsonEntry

// ============================================================================
// TYPED CONTRACT — spec: json-adapters (change: add-typesafe4s-sdk, spec 9/10)
//
// Gate 1 artifact. Module: typesafe4s-zio-json.
//
// Approved mechanism (user decision, option B): validated wrapper + total
// given. `JsonEntry.of` is the only constructor — a checked crossing that
// returns Left naming the dotted path of the first non-entry-shaped member.
// `StateEncoder.encode` is total (`A => Entry`), so an unconditional given
// for zio.json.ast.Json could not honestly exist; the wrapper discharges the
// invariant at construction, making the given honest — the same pattern
// Entry itself uses (checked fromJson, total consumers).
// ============================================================================

private object JsonAdapterTypeContract {

  // the checked crossing — the only way a zio-json ast.Json becomes a JsonEntry
  def witnessOf(value: zio.json.ast.Json): Either[String, JsonEntry] =
    JsonEntry.of(value)

  // the total given — resolves for the wrapper, NOT for the raw library type
  // (the compile-negative suite keeps it absent)
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
//   `conversion-is-faithful-order-included` — entry-shaped library values
//      convert to Entries rendering identically (members, order, nesting)
//   `a-refusal-names-a-real-violation` — a planted number/boolean at a drawn
//      position produces Left naming that position
//   `nothing-stays-nothing` — the library's null converts to Entry.nothing
//
// Compile-Negative Obligation — outside/JsonAdapterCompileNegativeSuite:
//   * `summon[StateEncoder[zio.json.ast.Json]]` must NOT resolve — no
//     unconditional given for a type that can hold numbers/booleans
// ============================================================================
