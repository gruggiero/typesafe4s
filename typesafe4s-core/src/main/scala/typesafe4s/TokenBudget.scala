package typesafe4s

import typesafe4s.json.Json

// ============================================================================
// spec: question-batching — the TokenBudget concept's actions, realized once
// in core (Implementation Anchors: token estimator + division are pure
// functions in typesafe4s-core).
//
// `limit` is the documented shared budget — ~32,000 units across state and
// questions together, approximate by the docs' own wording.
//
// The estimate is deliberately approximate: a fixed service-overhead
// constant PLUS ceil(rendered UTF-8 BYTES / 3) for the request body.
//
// CONFIRMED against a live key 2026-09-19 (task 12.3): the service's
// reported input_tokens are NOT proportional to the request body alone —
// it counts a fixed prompt scaffold the caller never sees. Observed:
// ~220 rendered bytes reported 272; ~258 bytes reported 286 — a scaffold
// of roughly 250–260 tokens on top of ~4-bytes-per-token content
// accounting. bytes/3 alone UNDERSTATED small requests by ~3.7×
// (74 vs 272) — the missing term was the scaffold, not the divisor:
// at scale bytes/3 already clears scaffold + bytes/4.
//
// `ServiceOverhead` = 512 ≈ 2× the observed scaffold — headroom for the
// template growing. Counting BYTES rather than chars keeps the content
// term honest for non-ASCII payloads — a BMP non-ASCII char occupies
// 2–4 bytes and tokenizes at roughly one token per char, where chars/3
// would understate ~3× on CJK (observed: the CJK+emoji exchange reported
// 286 and was still understated pre-correction).
// ============================================================================
object TokenBudget {

  val limit: TokenEstimate = TokenEstimate.of(32_000)

  private val BytesPerToken = 3

  // the fixed prompt scaffold the service counts on every exchange —
  // invisible in the request body, measured live at ~250–260 tokens;
  // carried at 2× for template-growth headroom (task 12.3, 2026-09-19)
  private val ServiceOverhead = 512

  // `estimate`'s signature carries no model — a fixed-length placeholder
  // longer than any published model name keeps the estimate's envelope
  // contribution honest with headroom
  private val ModelHeadroom = "m" * 128

  // the `fit` action's result: the estimate, and whether it is within the
  // limit — one judgement covering state AND questions together
  final case class Fit(estimate: TokenEstimate, within: Boolean)

  // TokenBudget/fit — "estimate the size of a state and question set
  // together, report that estimate to callers, and describe it as
  // approximate"
  def estimate[A](state: A, questions: QuestionSet)(using enc: StateEncoder[A]): TokenEstimate = {
    val bytes = Json.render(SystemOne.renderRequest(state, ModelHeadroom, questions)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
    TokenEstimate.of(ServiceOverhead + (bytes + BytesPerToken - 1) / BytesPerToken)
  }

  def fit[A](state: A, questions: QuestionSet)(using enc: StateEncoder[A]): Fit = {
    val e = estimate(state, questions)
    Fit(e, e.within(limit))
  }

  // TokenBudget/split — divide a question set into batches that each fit
  // alongside the state, every question in exactly one batch, in order.
  // Left names the question that cannot fit even alone — the local refusal,
  // before any exchange (Scenario: One question that cannot ever fit).
  //
  // Packing is greedy and ordered: a question joins the open batch while
  // the REAL estimate of the accumulated set stays within the limit — the
  // judgement is the same `estimate` the property asserts on, so a batch
  // can never be emitted unfitted. Each estimate call renders at most
  // ~limit-sized input, so the division is O(n × limit), not quadratic.
  def split[A](state: A, questions: QuestionSet)(using enc: StateEncoder[A]): Either[TypesafeException.InvalidQuestion, List[Batch]] = {
    val entries = questions.entries.toList

    entries.find(e => !estimate(state, QuestionSet(e)).within(limit)) match {
      case Some((name, _)) =>
        Left(
          TypesafeException.InvalidQuestion(
            Some(name),
            "the question cannot fit within the token budget even alone beside the state"
          )
        )
      case None            =>
        @scala.annotation.tailrec
        def pack(
          remaining: List[(String, Question[?])],
          current: List[(String, Question[?])],
          done: List[List[(String, Question[?])]]
        ): List[List[(String, Question[?])]] =
          remaining match {
            case Nil           => if (current.isEmpty) done else done :+ current.reverse
            case entry :: rest =>
              val trial = entry :: current
              if (current.nonEmpty && !estimate(state, QuestionSet(trial.head, trial.tail*)).within(limit))
                pack(rest, List(entry), done :+ current.reverse)
              else pack(rest, entry :: current, done)
          }
        Right(
          pack(entries, Nil, Nil).zipWithIndex.map { case (qs, i) =>
            Batch(i, QuestionSet(qs.head, qs.tail*))
          }
        )
    }
  }
}
