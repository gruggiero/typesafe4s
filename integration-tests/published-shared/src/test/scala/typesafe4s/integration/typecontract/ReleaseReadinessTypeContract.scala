package typesafe4s.integration.typecontract

import typesafe4s.TypesafeException
import typesafe4s.client.TypesafeConfig

// ============================================================================
// TYPED CONTRACT — spec: release-readiness (change: add-typesafe4s-sdk, spec 10/10)
//
// This spec introduces NO new domain types — Concepts Introduced: none. Its
// surface is release machinery: a credential-gated live suite, per-row
// examples, the README, the CI workflow and publish assertions. The contract
// therefore fixes the shape of the one piece of compile-checked machinery the
// live suite needs — the credential gate — plus the observation record a
// credentialed run emits for the reconciliation ledger.
//
// Artifact obligations this contract cannot type (they are build facts,
// discharged by `checkReleaseReadiness` wired into `sbt check`):
//   - `examples/<row>/src/main/scala` carries a quickstart and a
//     confidence-gated routing example for each of the five published rows
//   - README.md states `io.github.gruggiero` coordinates, the Lightbend
//     disambiguation, the quickstart and the 0.1 backend scope
//   - `.github/workflows/ci.yml` builds/tests/lints every row and runs
//     `sbt ci-release` on tags
//   - the published set is core + five client rows + four adapters + the
//     vendored compat-ce; the future anchor row, root, integration-tests,
//     ce-conformance and examples never publish
// ============================================================================

// The credential gate every live test consults — Credential/resolve's
// documented environment variable, and nothing else.
//
// Two distinct questions, two members (Ring-8 fix F1): `credentialed`
// answers "is a live credential present" — the ONLY condition under which a
// live test may skip. `config` then resolves the full configuration and
// propagates invalid settings as a Left the caller must fail on — an
// unparseable sibling variable with the credential present is a broken run,
// not an absent credential, and must never collapse into a skip.
object LiveGate {

  /**
    * Is the documented credential variable present and non-blank? Consults
    * `TypesafeConfig.apiKeyEnv` and nothing else. `false` ⇒ every live test
    * skips (Requirement: A real exchange is exercised when a credential is
    * present).
    */
  def credentialed(env: String => Option[String]): Boolean =
    env(TypesafeConfig.apiKeyEnv).exists(!_.isBlank)

  /**
    * The resolved live configuration — the credential plus the documented
    * env-derived settings (baseUrl/model/log level), so a live run can be
    * pointed at a non-default deployment. `Left` names the invalid setting;
    * a credentialed caller fails on it, never skips.
    */
  def config(env: String => Option[String]): Either[TypesafeException, TypesafeConfig] =
    TypesafeConfig.resolve(environment = env)
}

// What one credentialed run observed — the content of the reconciliation
// record (Requirement: The documented wire facts are confirmed against the
// live service). Facts that cannot be forced (a rate-limited or overloaded
// response) are OBSERVED, not asserted — the record either confirms them or
// reports "not observed", and the MUST-CONFIRM ledger entries stay pending
// until a run observes them.
final case class LiveReconciliation(
  // `GET /v1/models`: Left names the decode failure, Right the model count
  modelsDecoded: Either[String, Int],
  // the raw `GET /v1/models` body the run observed (truncated to 8K) — when
  // the strict decode diverges, this is the evidence the codec correction
  // is made against; without it a decode failure loses the observed shape
  modelsRawBody: String,
  // header names seen carrying retry delay advice on any 429/529 response
  // observed during the run — empty when none was observed
  retryAdviceHeaders: List[String],
  // status codes seen during the run — 529 presence confirms or refutes
  // the overloaded member's reachability
  statusesSeen: List[Int],
  // each exchange whose local estimate understated the service's reported
  // input accounting — empty means the estimate never understated
  understatedEstimates: List[String],
  // each exchange where the service reported no input accounting at all —
  // the margin check is unobservable for these (Ring-8 F2: absent
  // accounting must be a finding, never a vacuous pass)
  unreportedAccounting: List[String]
)
