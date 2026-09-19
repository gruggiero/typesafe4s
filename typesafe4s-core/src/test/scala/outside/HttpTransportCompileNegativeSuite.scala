package outside

// spec: http-transport — Compile-Negative Obligations
//
// This suite lives in package `outside` — NOT under `typesafe4s` — so the
// refusals it proves hold for a caller of the SDK, not just for code that
// can see its internals.
//
// Both obligations are green-by-design: the refusals are properties of the
// Gate-1-approved surface itself (a required named parameter; a capability
// that is not on the core's classpath), so they hold on the contract and
// must keep holding on the implementation — they cannot be red.
class HttpTransportCompileNegativeSuite extends munit.FunSuite {

  // spec: http-transport — Compile-Negative: Reaching a network from the
  // pure core — "the capability is not on its path". `typesafe4s.client`
  // does not exist on the core test classpath, so referencing the
  // capability does not build — before AND after the transport ships:
  // the dependency direction is client → core, never the reverse.
  test("the exchange capability cannot be referenced from the pure core") {
    val errors = compileErrors("""import typesafe4s.client.Transport""")
    assert(errors.nonEmpty, "the transport capability resolved inside the pure core")
    assert(
      errors.toLowerCase.contains("typesafe4s"),
      s"the refusal does not name the unreachable package: $errors"
    )
  }

  // spec: http-transport — Compile-Negative: Sending a request with no
  // authorization — `key` is a required parameter of the only construction
  // path, so omitting it is a compile error naming the parameter.
  test("a request with no authorization does not compile") {
    val errors = compileErrors(
      """typesafe4s.HttpRequest.exchange(typesafe4s.HttpMethod.Post, "/v1/systemone", Some("{}"), configured = Nil)"""
    )
    assert(errors.nonEmpty, "a request with no credential compiled")
    assert(
      errors.contains("key"),
      s"the refusal does not name the missing credential parameter: $errors"
    )
  }

  // spec: http-transport — Compile-Negative: the raw constructor is not a
  // back door around the credential path (contract T4).
  test("a request cannot be built around the credential path") {
    val errors = compileErrors(
      """typesafe4s.HttpRequest(typesafe4s.HttpMethod.Post, "/v1/systemone", Nil, Some("{}"))"""
    )
    assert(errors.nonEmpty, "the raw constructor was reachable")
    assert(
      errors.contains("HttpRequest"),
      s"the refusal does not name the construction path: $errors"
    )
  }

  // Positive control: the sanctioned path compiles from outside the
  // package — an inaccessible construction mechanism fails this suite, not
  // just the negatives.
  test("a request carrying its credential compiles") {
    assertEquals(
      compileErrors(
        """typesafe4s.HttpRequest.exchange(typesafe4s.HttpMethod.Post, "/v1/systemone", Some("{}"), typesafe4s.ApiKey.of("sk"))"""
      ),
      "",
      "the credential path did not compile"
    )
  }

  // spec: http-transport — contract T10 (Ring 8 finding F4): the SPI tells
  // a caller-supplied transport to surface its exchange faults as
  // `ConnectionFailed` so they join retry classification. That guidance is
  // only real if the member is constructible from OUTSIDE `typesafe4s` —
  // it carries no service trace, so a caller raising it fabricates nothing
  // the service said.
  test("a caller transport can raise ConnectionFailed") {
    val failure = typesafe4s.TypesafeException.ConnectionFailed(new java.io.IOException("refused"))
    assertEquals(failure.getCause.getMessage, "refused")
    assertEquals(
      compileErrors("""typesafe4s.TypesafeException.ConnectionFailed(new java.io.IOException("x"))"""),
      "",
      "a caller could not construct the transport-local fault member"
    )
    // the service-reported members stay closed — a caller still cannot
    // fabricate what the service said
    assert(
      compileErrors("""typesafe4s.TypesafeException.InternalServer(500, None, "x")""").nonEmpty,
      "a caller fabricated a service-reported failure"
    )
  }
}
