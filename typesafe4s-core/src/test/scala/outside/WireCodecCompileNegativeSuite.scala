package outside

// spec: wire-codec — Compile-Negative Obligations
//
// These negatives hold against the type STRUCTURE itself — the opaque Entry,
// its restricted smart constructors and the non-empty QuestionSet — so they
// pass pre-implementation (green-by-design) and guard the surface after it.
class WireCodecCompileNegativeSuite extends munit.FunSuite {

  // spec: wire-codec — Compile-Negative: an Entry holding a number or a boolean at top level
  test("an Entry cannot hold a number or a boolean at top level") {
    assert(compileErrors("""typesafe4s.Entry(typesafe4s.json.Json.JNumber(1.0))""").nonEmpty)
    assert(compileErrors("""typesafe4s.Entry(typesafe4s.json.Json.JBool(true))""").nonEmpty)
  }

  // spec: wire-codec — Compile-Negative: the restriction is recursive — an
  // Entry's children are Entry, so a raw Json number cannot be nested either
  test("an Entry cannot hold a number anywhere in its tree") {
    assert(compileErrors("""typesafe4s.Entry.obj("count" -> typesafe4s.json.Json.JNumber(1.0))""").nonEmpty)
    assert(compileErrors("""typesafe4s.Entry.arr(typesafe4s.json.Json.JBool(false))""").nonEmpty)
  }

  // spec: wire-codec — Compile-Negative: rendering a request with no questions
  test("a request with no questions cannot be rendered") {
    assert(compileErrors("""typesafe4s.QuestionSet()""").nonEmpty)
    assert(
      compileErrors(
        """typesafe4s.SystemOne.renderRequest(typesafe4s.Entry.text("s"), "m", typesafe4s.QuestionSet())"""
      ).nonEmpty
    )
    // the generated case-class apply/copy are as private as the constructor
    // (Scala 3 semantics) — an empty set cannot slip in through them either
    assert(compileErrors("""typesafe4s.QuestionSet(scala.collection.immutable.ListMap.empty)""").nonEmpty)
    assert(
      compileErrors(
        """typesafe4s.QuestionSet("a" -> typesafe4s.Noul(typesafe4s.Entry.text("n?"))).copy(entries = scala.collection.immutable.ListMap.empty)"""
      ).nonEmpty
    )
  }
}
