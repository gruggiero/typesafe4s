package outside

// spec: effect-portability — Scenario: Shared sources name no ecosystem
// (the classpath-level half). This row carries the Ox library; the other
// four ecosystems' types do not resolve at all — so nothing written
// against them could have slipped into the shared sources this row
// compiles.
//
// Green-by-design: classpath isolation is a build fact — it holds on the
// contract and cannot be red.
class EcosystemIsolationSuite extends munit.FunSuite {

  test("the row's own ecosystem resolves and the other four do not") {
    assertEquals(
      compileErrors("""val x: ox.Ox ?=> Int = ???"""),
      "",
      "this row's own ecosystem did not resolve"
    )
    assert(compileErrors("""val x: zio.ZIO[Any, Nothing, Int] = ???""").nonEmpty, "zio resolved on the ox row")
    assert(compileErrors("""val x: cats.effect.IO[Int] = ???""").nonEmpty, "cats-effect resolved on the ox row")
    assert(compileErrors("""val x: kyo.<[Int, kyo.Abort[Throwable]] = ???""").nonEmpty, "kyo resolved on the ox row")
    assert(
      compileErrors("""val x: org.apache.pekko.stream.scaladsl.Source[Int, org.apache.pekko.NotUsed] = ???""").nonEmpty,
      "pekko resolved on the ox row"
    )
  }

  // spec: effect-portability — Positive controls (row-specific): on the ox
  // row the client's effect is a context function, so a call site must
  // supply the scope — `ox.supervised` — exactly as a real caller would.
  test("the published operations compile") {
    assertEquals(
      compileErrors(
        """val c: typesafe4s.TypesafeClient = ??? ; ox.supervised { c.systemOneDynamic("subject")(Seq("q" -> typesafe4s.Noul(typesafe4s.Entry.text("i")))) }"""
      ),
      "",
      "the dynamic operation did not compile"
    )
    assertEquals(
      compileErrors(
        """val c: typesafe4s.TypesafeClient = ??? ; ox.supervised { c.systemOne("subject")((q = typesafe4s.Noul(typesafe4s.Entry.text("i")))) }"""
      ),
      "",
      "the typed operation did not compile"
    )
  }

  // spec: question-model (carried at the portability layer) — an empty
  // question set written in source is refused before the program runs, and
  // the refusal names the reason.
  test("an empty question set does not compile") {
    val errors = compileErrors(
      """val c: typesafe4s.TypesafeClient = ??? ; ox.supervised { c.systemOne("subject")(EmptyTuple) }"""
    )
    assert(errors.nonEmpty, "an empty question set compiled")
    assert(
      errors.contains("at least one question"),
      s"the refusal does not carry the named reason: $errors"
    )
  }
}
