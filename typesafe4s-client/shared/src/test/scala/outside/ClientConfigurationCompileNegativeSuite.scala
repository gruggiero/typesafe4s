package outside

// spec: client-configuration — Compile-Negative Obligations
//
// This suite lives in package `outside` — NOT under `typesafe4s` — so the
// refusals it proves hold for a caller of the SDK, not just for code that
// can see its internals.
final class ClientConfigurationCompileNegativeSuite extends munit.FunSuite {

  // spec: client-configuration — Requirement: A secret is never shown. The
  // raw bearer pair was reachable through the public `authorization`
  // extension — a RED obligation until the accessor is package-sealed.
  test("the credential's authorization pair cannot be read from outside") {
    val errors = compileErrors(
      """typesafe4s.ApiKey.of("k").authorization"""
    )
    assert(errors.nonEmpty, "the raw bearer pair was readable from outside the SDK")
  }

  // spec: client-configuration — Requirement: A secret is never shown —
  // green-by-design: the wrapped value is a private field, so no accessor
  // name resolves.
  test("the credential's raw value cannot be read") {
    val errors = compileErrors("""typesafe4s.ApiKey.of("k").value""")
    assert(errors.nonEmpty, "the raw credential string was readable")
  }

  // spec: client-configuration — Requirement: A credential is required —
  // green-by-design: `apiKey` is a required field of the product, so a
  // configuration without it does not compile naming the missing member.
  test("a configuration with no credential does not compile") {
    val errors = compileErrors(
      """typesafe4s.client.TypesafeConfig(baseUrl = "u", model = "m", allowance = scala.concurrent.duration.DurationInt(10).seconds, retryPolicy = typesafe4s.RetryPolicy.default, headers = Nil, logLevel = typesafe4s.client.LogLevel.Info)"""
    )
    assert(errors.nonEmpty, "a configuration with no credential compiled")
    assert(
      errors.contains("apiKey"),
      s"the refusal does not name the missing credential: $errors"
    )
  }

  // spec: client-configuration — Gate-1 amendment A2 — green-by-design:
  // the construction of an invalid-configuration failure is sealed to the
  // SDK, like every other member of the family.
  test("an invalid-configuration failure cannot be forged from outside") {
    val errors = compileErrors(
      """typesafe4s.TypesafeException.InvalidConfiguration("TYPESAFE_LOG_LEVEL", "x")"""
    )
    assert(errors.nonEmpty, "an invalid-configuration failure was forgeable from outside")
  }

  // Positive control: the sanctioned path — a fully-formed configuration
  // into the facade — compiles from outside the package.
  test("a configured client compiles") {
    assertEquals(
      compileErrors(
        """typesafe4s.TypesafeClient.of(typesafe4s.client.TypesafeConfig(typesafe4s.ApiKey.of("k"), "u", "m", scala.concurrent.duration.DurationInt(10).seconds, typesafe4s.RetryPolicy.default, Nil, typesafe4s.client.LogLevel.Info))"""
      ),
      "",
      "the sanctioned construction path did not compile"
    )
  }
}
