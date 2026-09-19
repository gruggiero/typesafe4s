package outside

// spec: retry-policy — Compile-Negative Obligations
//
// This suite lives in package `outside` — NOT under `typesafe4s` — so the
// refusals it proves hold for a caller of the SDK, not just for code that can
// see its internals.
//
// Both negatives exercise `inline if` checks inside `RetryPolicy.apply` —
// before that method exists they are red (the call does not resolve; the
// asserted limit text cannot appear), which is the correct polarity for this
// spec. The distinctive-text assertions avoid the echoed-source trap the
// question-model negatives recorded.
class RetryPolicyCompileNegativeSuite extends munit.FunSuite {

  // spec: retry-policy — Compile-Negative: A policy with a negative attempt
  // count — refused, naming the attempt count
  test("a policy with a negative attempt count does not compile") {
    val errors = compileErrors("""typesafe4s.RetryPolicy(attempts = -1)""")
    assert(errors.nonEmpty, "a negative attempt count compiled")
    assert(
      errors.contains("attempt count may not be negative"),
      s"the refusal does not name the attempt count: $errors"
    )
  }

  // spec: retry-policy — Compile-Negative: A policy with a jitter outside
  // zero to one — refused, naming the fraction's range
  test("a policy with jitter outside zero to one does not compile") {
    val below = compileErrors("""typesafe4s.RetryPolicy(jitter = -0.1)""")
    val above = compileErrors("""typesafe4s.RetryPolicy(jitter = 1.5)""")
    assert(below.nonEmpty, "a jitter below zero compiled")
    assert(above.nonEmpty, "a jitter above one compiled")
    assert(
      below.contains("fraction between 0 and 1"),
      s"the refusal does not name the fraction's range: $below"
    )
    assert(
      above.contains("fraction between 0 and 1"),
      s"the refusal does not name the fraction's range: $above"
    )
  }

  // Positive control: the same literal path compiles from outside the
  // package — an inaccessible construction mechanism fails this suite, not
  // just the negatives. The literal calls are STRING snippets, not direct
  // calls: `apply`'s inline check reduces at every call site, so a mutation
  // of `apply`'s literal defaults would break this file's own compilation
  // before the suite could run (stryker setup); snippets typecheck at run
  // time against whatever signature shipped, so such mutants fail HERE —
  // as killed mutants — instead of breaking the environment.
  test("a literal policy compiles and a dynamic one resolves through `of`") {
    assertEquals(
      compileErrors("""typesafe4s.RetryPolicy(attempts = 1, jitter = 0.5)"""),
      "",
      "a valid literal policy did not compile"
    )
    assertEquals(
      compileErrors("""typesafe4s.RetryPolicy()"""),
      "",
      "the no-argument literal defaults did not compile"
    )
    assertEquals(
      compileErrors("""typesafe4s.RetryPolicy(attempts = 1)"""),
      ""
    )
    val dynamic = typesafe4s.RetryPolicy.of(attempts = 1, jitter = 0.5)
    assertEquals(dynamic.attempts, 1)
    assertEquals(dynamic.jitter, 0.5)
  }
}
