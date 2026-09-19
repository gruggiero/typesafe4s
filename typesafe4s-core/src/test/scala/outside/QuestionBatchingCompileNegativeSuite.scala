package outside

// spec: question-batching — Compile-Negative Obligations
//
// This suite lives in package `outside` — NOT under `typesafe4s` — so the
// refusals it proves hold for a caller of the SDK, not just for code that
// can see its internals.
//
// The bound negatives exercise the `inline if` check inside
// `ConcurrencyBound.apply` — green-by-design, since the literal check IS
// the approved type structure (promoted as part of the Gate-1 surface).
// The estimate negatives hold against the type's shape: TokenEstimate
// exposes no exact-count conversion — the asserted members simply do not
// exist; a future addition that introduces one turns these tests red.
class QuestionBatchingCompileNegativeSuite extends munit.FunSuite {

  // spec: question-batching — Compile-Negative: a concurrency bound of zero
  // — refused, naming the positive rule
  test("a concurrency bound of zero is refused") {
    val errors = compileErrors("""typesafe4s.ConcurrencyBound(0)""")
    assert(errors.contains("positive"), s"the refusal does not name the positive rule: $errors")
  }

  // spec: question-batching — Compile-Negative: a negative bound
  test("a negative concurrency bound is refused") {
    val errors = compileErrors("""typesafe4s.ConcurrencyBound(-3)""")
    assert(errors.contains("positive"), s"the refusal does not name the positive rule: $errors")
  }

  // spec: question-batching — Compile-Negative: treating an estimate as an
  // exact count — no such conversion exists; the estimate's only numeric
  // view is named `approximateTokens`
  test("an estimate offers no exact token count") {
    assert(
      compileErrors("""(??? : typesafe4s.TokenEstimate).tokens""").nonEmpty,
      "a `tokens` accessor resolved — an exact-count conversion exists"
    )
    assert(
      compileErrors("""(??? : typesafe4s.TokenEstimate).toInt""").nonEmpty,
      "a `toInt` conversion resolved — an exact-count conversion exists"
    )
    assert(
      compileErrors("""typesafe4s.TokenEstimate.exact(??? : typesafe4s.TokenEstimate)""").nonEmpty,
      "an `exact` conversion resolved — an exact-count conversion exists"
    )
  }

  // positive control — the documented approximate view DOES exist, so the
  // negatives above are not vacuous
  test("the approximate view exists") {
    assert(compileErrors("""(??? : typesafe4s.TokenEstimate).approximateTokens""").isEmpty)
  }
}
