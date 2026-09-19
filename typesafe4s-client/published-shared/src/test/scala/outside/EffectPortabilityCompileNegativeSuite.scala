package outside

// spec: effect-portability — Compile-Negative Obligations
//
// This suite lives in package `outside` — NOT under `typesafe4s` — so the
// refusals it proves hold for a caller of the SDK, not just for code that
// can see its internals. It compiles into every PUBLISHED row (the
// baseline row has no facade and does not compile it).
//
// Green-by-design: every refusal here is a property of the Gate-1-approved
// surface itself (package-private construction and conversions; the
// compile-time refusal inside `fromNamedTuple`), so they hold on the
// contract and must keep holding on the implementation — they cannot be
// red.
//
// The row-dependent compile assertions — the published operations
// compiling, and the empty-set refusal carrying its named reason — live in
// each row's EcosystemIsolationSuite: a `c.systemOne(...)` call produces
// each row's OWN effect type, which on the ox row is a context function
// that auto-applies and demands `summon[Ox]`, so the snippet cannot be
// spelled row-agnostically.
class EffectPortabilityCompileNegativeSuite extends munit.FunSuite {

  // spec: effect-portability — Compile-Negative: Exposing the carrier in a
  // published operation's type. The carrier client can only be produced
  // package-internally; a caller has no path to a `Client[CIO]`.
  test("the carrier client cannot be constructed by a caller") {
    val errors = compileErrors("""typesafe4s.client.Client.cio""")
    assert(errors.nonEmpty, "the carrier client construction was reachable")
  }

  // spec: effect-portability — the facade's two conversions are the
  // adapter's internals, not caller operations: a caller could bypass
  // neither side of the round-trip.
  test("a facade's conversions are not caller-reachable") {
    assert(compileErrors("""typesafe4s.TypesafeClient.lower""").nonEmpty, "lower was caller-reachable")
    assert(compileErrors("""typesafe4s.TypesafeClient.lift""").nonEmpty, "lift was caller-reachable")
  }

  // spec: effect-portability — Scenario: A caller never meets the carrier.
  // `Client` is invariant in F, so no `TypesafeClient` — the published
  // client type — can ever be a `Client[CIO]`, on any row.
  test("no published client type is carrier-typed") {
    val errors = compileErrors(
      """val c: typesafe4s.client.Client[kyo.compat.CIO] = typesafe4s.TypesafeClient.of(null)"""
    )
    assert(errors.nonEmpty, "a published client typed as the carrier compiled")
  }

  // spec: effect-portability — the package-private adapter cannot be
  // extended by a caller either: the "two conversions" mechanism is the
  // SDK's own, closed against caller-substituted behaviour.
  test("the shared adapter is not caller-constructible") {
    val errors = compileErrors(
      """new typesafe4s.client.LoweredClient[Option](null) { protected def lower[A](c: kyo.compat.CIO[A]): Option[A] = None; protected def lift[A](o: Option[A]): kyo.compat.CIO[A] = ??? }"""
    )
    assert(errors.nonEmpty, "the shared adapter was constructible by a caller")
  }

}
