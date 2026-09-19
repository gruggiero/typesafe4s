package typesafe4s.typecontract

import typesafe4s.{Batch, ConcurrencyBound, QuestionSet, StateEncoder, TokenBudget, TokenEstimate, TypesafeException}

// ============================================================================
// TYPED CONTRACT — spec: question-batching (change: add-typesafe4s-sdk,
// spec 8/8)
//
// Gate 1 artifact, APPROVED. The declarations were promoted to
// typesafe4s-core/src/main/scala/typesafe4s/{TokenEstimate,ConcurrencyBound,
// Batch,TokenBudget}.scala at Step 3; this file remains as a living
// compile-time assertion — if the implementation drifts from the approved
// surface, this file stops compiling.
//
// Approved surface (Gate 1):
//   opaque type TokenEstimate = Int — `approximateTokens` + `within`
//   final case class Batch private[typesafe4s] (index, questions)
//   opaque type ConcurrencyBound = Int — `default` (4), `apply(inline n)`
//     literal-checked, `of` runtime-checked, extension `value`
//   object TokenBudget — `limit`, `estimate`, `fit` (Fit case class),
//     `split` → Either[InvalidQuestion, List[Batch]]
//
// Approved design decisions:
//   Q1  Arrival-order emission (the spec's literal scenario text).
//   Q2  The batched surface is dynamic-only — `Evaluation[AnswerSet]` per
//       batch; a subset can never fill the caller's AnswerOf product.
//   Q3  `ConcurrencyBound` lives in core; `default = 4`.
//   Q4  `approximateTokens` is the ONLY numeric view — no exact-count
//       conversion exists to forbid; the compile-negative asserts absence.
//   R8-A1 (Ring-8 amendment): the estimate counts rendered UTF-8 BYTES
//       (ceil(bytes/3)) — a char count would understate ~3× on CJK at
//       ~1 token/char; ASCII payloads are unchanged.
// ============================================================================

private object QuestionBatchingTypeContract {

  // surface witnesses — each stops compiling if the promoted surface drifts

  val estimateApproxSig: TokenEstimate => Int                      = e => e.approximateTokens
  val estimateWithinSig: (TokenEstimate, TokenEstimate) => Boolean = (e, l) => e.within(l)

  val boundDefaultSig: ConcurrencyBound           = ConcurrencyBound.default
  val boundOfSig: Int => Option[ConcurrencyBound] = ConcurrencyBound.of
  val boundValueSig: ConcurrencyBound => Int      = b => b.value
  // `apply`'s inline check reduces at call sites — a positive literal
  // witnesses the member; refusal is witnessed by the compile-negative
  // suite (`ConcurrencyBound(0)`/`(-3)` must not typecheck)
  val boundApplySig: ConcurrencyBound             = ConcurrencyBound(4)

  val budgetLimitSig: TokenEstimate = TokenBudget.limit

  def witnessEstimate(state: String, qs: QuestionSet): TokenEstimate =
    TokenBudget.estimate(state, qs)

  def witnessFit(state: String, qs: QuestionSet): TokenBudget.Fit =
    TokenBudget.fit(state, qs)

  def witnessSplit(
    state: String,
    qs: QuestionSet
  ): Either[TypesafeException.InvalidQuestion, List[Batch]] =
    TokenBudget.split(state, qs)

  def witnessBatch(b: Batch): (Int, QuestionSet) = (b.index, b.questions)
}

// ============================================================================
// Property obligations (Ring 2) — typesafe4s.QuestionBatchingProperties
// (core test sources; division and estimation are pure):
//   P1 `dividing-loses-nothing` — genOversizedSet: constructive; state size
//      + 1–200 questions sized so the total USUALLY exceeds the budget;
//      boundary cases by construction (fits exactly, one unit over, every
//      question alone in its batch). Asserts union of batch names == set
//      names, no repeats. classify: batch-count bucket, fit-whole.
//   P2 `every-batch-fits` — genOversizedSet reused; a near-budget question
//      arises by construction. classify: largest question / budget.
//   P3 `unfittable-question-is-refused-not-divided` — genUnfittableQuestion:
//      offender drawn to exceed remaining budget by a positive margin,
//      placed at a drawn position. Asserts Left naming the offender and
//      zero exchanges attempted. classify: offender position.
//   + `estimate-counts-non-ascii-conservatively` — Ring-8 regression:
//      n three-byte chars must be charged ≥ n units.
//   + scenario tests: `several-questions-one-exchange` (stand-in counts
//     exchanges), `estimate-does-not-understate` (estimate >= simulated
//     service accounting; MUST-CONFIRM margin vs real responses — 12.3).
//
// Compile-Negative Obligations — outside/QuestionBatchingCompileNegativeSuite:
//   * `ConcurrencyBound(0)` / `ConcurrencyBound(-3)` — refuses, naming the
//     positive rule
//   * `estimate.tokens` / any exact-count conversion — refuses (no such
//     member exists; the test keeps it absent)
// ============================================================================
