package outside

// spec: json-adapters — Compile-Negative Obligations
//
// This suite lives in package `outside` — NOT under `typesafe4s` — so the
// refusals it proves hold for a caller of the SDK, not just for code that
// can see its internals.
//
// jsoniter-scala ships no public AST, so the adapter's input is encoded JSON.
// The refusal: raw bytes/text are not implicitly usable as state — the
// checked `JsonEntry.of` is the only way in.
class JsonAdapterCompileNegativeSuite extends munit.FunSuite {

  // spec: json-adapters — Compile-Negative: encoded JSON is not implicitly
  // usable as state
  test("raw JSON bytes resolve no state encoder") {
    assert(
      compileErrors("""summon[typesafe4s.StateEncoder[Array[Byte]]]""").nonEmpty,
      "a StateEncoder[Array[Byte]] resolved — raw JSON bytes are implicitly usable as state"
    )
    assert(
      compileErrors("""summon[typesafe4s.StateEncoder[scala.collection.immutable.ArraySeq.ofByte]]""").nonEmpty,
      "a StateEncoder for a byte sequence resolved — raw JSON bytes are implicitly usable as state"
    )
  }

  // positive control — the wrapper DOES resolve (its given lives in
  // JsonEntry's companion, in implicit scope)
  test("the validated wrapper resolves a state encoder") {
    assert(compileErrors("""summon[typesafe4s.StateEncoder[typesafe4s.jsoniter.JsonEntry]]""").isEmpty)
  }
}
