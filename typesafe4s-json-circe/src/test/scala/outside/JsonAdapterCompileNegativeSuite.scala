package outside

// spec: json-adapters — Compile-Negative Obligations
//
// This suite lives in package `outside` — NOT under `typesafe4s` — so the
// refusals it proves hold for a caller of the SDK, not just for code that
// can see its internals.
//
// Green-by-design: `StateEncoder.encode` is total and `Entry` recursively
// forbids numbers/booleans, so no honest given can exist for the raw
// library type — the suite keeps it absent.
class JsonAdapterCompileNegativeSuite extends munit.FunSuite {

  // spec: json-adapters — Compile-Negative: the raw library type is not
  // implicitly usable as state; the checked `JsonEntry.of` is the only way in
  test("a raw circe Json resolves no state encoder") {
    assert(
      compileErrors("""summon[typesafe4s.StateEncoder[io.circe.Json]]""").nonEmpty,
      "a StateEncoder[io.circe.Json] resolved — an unconditional given exists for a type that can hold numbers/booleans"
    )
  }

  // positive control — the wrapper DOES resolve (its given lives in
  // JsonEntry's companion, in implicit scope)
  test("the validated wrapper resolves a state encoder") {
    assert(compileErrors("""summon[typesafe4s.StateEncoder[typesafe4s.circe.JsonEntry]]""").isEmpty)
  }
}
