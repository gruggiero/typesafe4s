package typesafe4s.integration

import scala.annotation.unused
import scala.concurrent.ExecutionContext

import kyo.compat.*
import munit.FunSuite

import typesafe4s.{Entry, HttpMethod, HttpRequest, Noul, QuestionSet, TokenBudget, TypesafeClient}
import typesafe4s.client.{Transport, TypesafeConfig}
import typesafe4s.integration.typecontract.{LiveGate, LiveReconciliation}

// ============================================================================
// TEST ORACLE — spec: release-readiness (change: add-typesafe4s-sdk, spec 10/10)
//
// The wire-fact reconciliation (task 12.3). What a live run can assert
// deterministically is asserted; what it can only observe (a 429/529 the
// service must choose to send) is recorded into the LiveReconciliation the
// run prints — the reconciliation record's content, which the human copies
// into state.md and the specs asserting each fact.
//
// Asserted live, every credentialed run:
//   * the models listing decodes against the real body (strict decoder)
//   * TokenBudget.estimate never understates usage.inputTokens, across
//     varied state/question sizes including non-ASCII content
//   * the service reports input accounting for every exchange (absent
//     accounting is a recorded finding, never a vacuous pass — Ring-8 F2)
// Observed live, recorded either way:
//   * which header names carried retry delay advice on any 429/529 seen
//   * whether the overloaded status was ever observed
// ============================================================================
final class LiveWireFactSuite extends FunSuite {

  @unused private given ExecutionContext = munitExecutionContext

  // spec: release-readiness — Requirement: The documented wire facts are
  // confirmed against the live service.
  test("live-wire-fact-reconciliation") {
    assume(LiveGate.credentialed(sys.env.get), s"no live credential — set ${TypesafeConfig.apiKeyEnv}")
    val cfg    = LiveGate
      .config(sys.env.get)
      .fold(e => fail(s"credential present but live config invalid: $e"), identity)
    val client = TypesafeClient.of(cfg)

    // three exchanges of deliberately varied size, including non-ASCII
    // (CJK + supplementary-plane emoji — the residual risk the token-budget
    // concept names)
    val exchanges = List(
      "small"   -> ("ok", QuestionSet("q" -> Noul(Entry.text("Is this a word?")))),
      "unicode" -> ("結果は良好です。Emoji: 🚀🎉", QuestionSet("q" -> Noul(Entry.text("Is this upbeat?")))),
      "larger"  -> (
        "lorem ipsum " * 400,
        QuestionSet(
          "a" -> Noul(Entry.text("Is this repetitive?")),
          "b" -> Noul(Entry.text("Is this English?"))
        )
      )
    )

    val program =
      for {
        // a raw exchange on the same transport path, FIRST — so a divergent
        // body or a non-2xx status is captured as evidence instead of the
        // test aborting before anything was observed. Released on every
        // exit path.
        raw     <- CIO.acquireReleaseWith(CIO.value(Transport.default))(_.release)(
                     _.exchange(
                       HttpRequest.exchange(HttpMethod.Get, s"${cfg.baseUrl}/v1/models", None, cfg.apiKey, cfg.headers)
                     )
                   )
        // Scenario: The models listing decodes against the real body —
        // liftToTry keeps a strict-decode divergence INSIDE the record
        // (modelsDecoded = Left) instead of aborting before the evidence
        // prints; the assertion below still fails the run on it
        modelsT <- TypesafeClient.lift(client.listModels).liftToTry
        // Scenario: The estimate does not understate real accounting —
        // every exchange's estimate is compared to reported inputTokens;
        // an exchange with NO reported input accounting is itself a
        // finding — the margin check is unobservable for it
        evals   <- exchanges.foldLeft(CIO.value(List.empty[(String, Long, Option[Long])])) { (acc, e) =>
                     val (label, stateAndQs) = e
                     val (state, qs)         = stateAndQs
                     acc.flatMap { done =>
                       TypesafeClient
                         .lift(client.systemOneDynamic(state)(qs.entries))
                         .map(ev => done :+ (label, TokenBudget.estimate(state, qs).approximateTokens.toLong, ev.usage.inputTokens))
                     }
                   }
        _       <- TypesafeClient.lift(client.close)
      } yield {
        val modelsDecoded = modelsT match {
          case scala.util.Success(models) => Right(models.size)
          case scala.util.Failure(t)      => Left(t.getMessage)
        }
        val understated   = evals.collect {
          case (label, est, Some(reported)) if est < reported =>
            s"$label: estimate $est < reported inputTokens $reported"
        }
        val unreported    = evals.collect { case (label, _, None) =>
          s"$label: the service reported no inputTokens — the margin check is unobservable"
        }
        val rec           = LiveReconciliation(
          modelsDecoded = modelsDecoded,
          modelsRawBody = raw.body.take(8192),
          retryAdviceHeaders = raw.headers.collect {
            case (n, _) if n.toLowerCase(java.util.Locale.ROOT).contains("retry") => n
          },
          statusesSeen = List(raw.status),
          understatedEstimates = understated,
          unreportedAccounting = unreported
        )
        // the reconciliation record — copy verbatim into state.md and the
        // asserting specs when a credentialed run observes it. Printed
        // BEFORE the assertions so a divergence still leaves its evidence.
        println(s"[live-reconciliation] $rec")
        modelsDecoded match {
          case Left(e)  =>
            fail(s"the live models body did not fit the strict decoder: $e — the observed shape is in the record above")
          case Right(n) => assert(n > 0, "the live models listing decoded to zero models")
        }
        assertEquals(
          unreported,
          Nil,
          "the service reported no input accounting — record the finding and re-check the margin fact"
        )
        assertEquals(
          understated,
          Nil,
          "the estimate understated the service's reported accounting — correct the divisor and the spec"
        )
      }
    program.unsafeRun
  }
}
