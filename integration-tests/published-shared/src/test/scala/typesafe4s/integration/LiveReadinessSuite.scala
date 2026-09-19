package typesafe4s.integration

import scala.annotation.unused
import scala.concurrent.ExecutionContext

import munit.FunSuite

import typesafe4s.{Entry, Noul, TypesafeClient}
import typesafe4s.client.TypesafeConfig
import typesafe4s.integration.typecontract.LiveGate

// ============================================================================
// TEST ORACLE — spec: release-readiness (change: add-typesafe4s-sdk, spec 10/10)
//
// Written from the spec BEFORE implementation. Compiled into every published
// row via the published-shared machinery — `TypesafeClient.lift` runs each
// row's own facade and its own conversions, so one green run here is five
// named green runs (Ring 5 row: Live exchange runs when credentialed).
//
// The gate is `LiveGate.credentialed` — the ONLY credential variable a live
// test consults is `TypesafeConfig.apiKeyEnv`. Absent, every live test skips
// via munit `assume` and the run stays green (Scenario: No credential, no
// failure). Present, `LiveGate.config` resolves the full configuration and a
// Left fails loudly — an invalid sibling setting is a broken run, never a
// skip (Ring-8 F1).
// ============================================================================
final class LiveReadinessSuite extends FunSuite {

  @unused private given ExecutionContext = munitExecutionContext

  // spec: release-readiness — Scenario: No credential, no failure.
  // The skip mechanism itself is asserted unconditionally: an absent
  // variable closes the gate, a blank one does not open it, and no other
  // variable's presence substitutes.
  test("live-suite-skips-without-credential") {
    assert(!LiveGate.credentialed(_ => None), "an absent credential must close the gate — the suite skips")
    assert(
      !LiveGate.credentialed(Map(TypesafeConfig.apiKeyEnv -> "   ").get),
      "a blank credential resolves as absent — a blank string can never construct an ApiKey"
    )
    assert(
      !LiveGate.credentialed(Map("UNRELATED_KEY" -> "k", TypesafeConfig.baseUrlEnv -> "https://x").get),
      "no other variable opens the gate"
    )
    assert(
      LiveGate.credentialed(Map(TypesafeConfig.apiKeyEnv -> "k").get),
      "the documented variable opens the gate"
    )
    // credential present but a sibling variable is invalid → a broken run,
    // not an absent credential: `config` reports it as a Left the caller
    // fails on — it must never collapse into a skip
    assert(
      LiveGate
        .config(Map(TypesafeConfig.apiKeyEnv -> "k", TypesafeConfig.logLevelEnv -> "not-a-level").get)
        .isLeft,
      "an invalid sibling setting must surface as a failure, not a skip"
    )
    // the presence check consults the credential variable alone; the full
    // resolution consults only documented names — a typo'd or extra
    // variable must not be read silently
    val consulted = scala.collection.mutable.ListBuffer.empty[String]
    LiveGate.credentialed { k => consulted += k; None }
    assertEquals(
      consulted.toSet,
      Set(TypesafeConfig.apiKeyEnv),
      "the presence check must consult the credential variable alone"
    )
    consulted.clear()
    LiveGate.config { k => consulted += k; None }
    assert(
      consulted.toSet.subsetOf(
        Set(TypesafeConfig.apiKeyEnv, TypesafeConfig.baseUrlEnv, TypesafeConfig.modelEnv, TypesafeConfig.logLevelEnv)
      ),
      s"the gate consulted undocumented variables: ${consulted.toSet}"
    )
    assert(consulted.contains(TypesafeConfig.apiKeyEnv), "the gate never consulted the credential variable")
  }

  // spec: release-readiness — Scenario: A real exchange per backend.
  // One real ask through this row's own facade and conversions; answers
  // and usage must come back observable. A service that reports no usage
  // at all is itself a wire-fact finding — the assertion names it.
  test("live-exchange-per-row") {
    assume(LiveGate.credentialed(sys.env.get), s"no live credential — set ${TypesafeConfig.apiKeyEnv}")
    val client  = TypesafeClient.of(
      LiveGate.config(sys.env.get).fold(e => fail(s"credential present but live config invalid: $e"), identity)
    )
    val program =
      for {
        eval <- TypesafeClient.lift(
                  client.systemOneDynamic("The sky is blue.")(
                    List("verifiable" -> Noul(Entry.text("Is this statement about the physical world?")))
                  )
                )
        _    <- TypesafeClient.lift(client.close)
      } yield {
        assert(eval.answers.answers.nonEmpty, "the live exchange returned no answers")
        assert(
          eval.usage.inputTokens.isDefined || eval.usage.outputTokens.isDefined,
          "the live exchange reported no usage accounting — itself a wire-fact finding"
        )
      }
    program.unsafeRun
  }
}
