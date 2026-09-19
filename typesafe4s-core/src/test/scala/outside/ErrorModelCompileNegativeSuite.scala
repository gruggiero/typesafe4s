package outside

// spec: error-model — Compile-Negative Obligations
//
// This suite lives in package `outside` — NOT under `typesafe4s` — so that
// `private[typesafe4s]` member construction is genuinely denied here. A suite
// inside the SDK's own package could not prove it.
class ErrorModelCompileNegativeSuite extends munit.FunSuite {

  // spec: error-model — Scenario: Omitting a member is reported
  //
  // DISCHARGE NOTE: the omission report is a WARNING (E029, "match may not be
  // exhaustive … would fail on pattern case: TypesafeException.Authentication…"),
  // escalated to a build failure by this project's -Werror. munit's
  // compileErrors typechecks snippets in an isolated context that does NOT
  // escalate warnings — it returns "" for a warning-only snippet (verified),
  // so the obligation cannot live as a compileErrors test. The living checks
  // are the exhaustive `memberName` matches in the oracle and the typecontract
  // witness: add a member to the family without updating them and
  // `sbt core/Test/compile` fails, naming the missing member. Spec table row
  // amended accordingly.

  // spec: error-model — Compile-Negative: constructing a family member from outside the SDK
  test("constructing a family member from outside the SDK does not compile") {
    val errors = compileErrors(
      """
      typesafe4s.TypesafeException.BadRequest(None, "invented")
      """
    )
    assert(errors.nonEmpty, "expected external construction to be rejected")
  }

  // spec: error-model — Compile-Negative: the refusal mapper is SDK-internal
  test("mapping a refusal from outside the SDK does not compile") {
    val errors = compileErrors(
      """
      typesafe4s.TypesafeException.fromResponse(400, Nil, "x")
      """
    )
    assert(errors.nonEmpty, "expected external fromResponse access to be rejected")
  }
}
