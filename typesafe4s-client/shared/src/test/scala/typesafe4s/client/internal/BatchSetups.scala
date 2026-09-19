package typesafe4s.client.internal

import scala.concurrent.duration.*

import kyo.compat.*

import typesafe4s.{Entry, HttpResponse, Noul, Question, QuestionSet, TokenBudget}

// ============================================================================
// TEST ORACLE fixtures — spec: question-batching. Sizing helpers shared by
// the carrier suite, the parity suite, and the per-row published-stream
// suites: a question is sized so its marginal estimate fills a chosen
// fraction of the budget beside the state, making "each question its own
// batch" a construction fact rather than a hoped-for draw.
// ============================================================================
private[typesafe4s] object BatchSetups {

  def smallQuestion(name: String): (String, Question[?]) =
    name -> Noul(Entry.text("state a fact"))

  def est(state: String, qs: QuestionSet): Int =
    TokenBudget.estimate(state, qs).approximateTokens

  // marginal estimate of one question of `chars` instruction length
  private def marginal(state: String, chars: Int): Int =
    est(state, QuestionSet("q" -> Noul(Entry.text("x" * chars)))) -
      est(state, QuestionSet("q" -> Noul(Entry.text(""))))

  // binary-search an instruction length whose marginal estimate ≈ target
  def questionSizedTo(state: String, name: String, targetTokens: Int): (String, Question[?]) = {
    @scala.annotation.tailrec
    def search(lo: Int, hi: Int): Int =
      if (hi - lo <= 1) lo
      else {
        val mid = lo + (hi - lo) / 2
        if (marginal(state, mid) <= targetTokens) search(mid, hi) else search(lo, mid)
      }
    name -> Noul(Entry.text("x" * search(0, 300_000)))
  }

  // n questions, each filling ~70% of the budget beside the state → n batches
  def eachAlone(state: String, n: Int): QuestionSet = {
    val room = TokenBudget.limit.approximateTokens - est(state, QuestionSet(smallQuestion("probe")))
    val qs   = (1 to n).toList.map(i => questionSizedTo(state, s"qqq$i", (room * 7) / 10))
    QuestionSet(qs.head, qs.tail*)
  }

  // a response covering every asked name, so whichever subset a batch
  // carries, the answer decodes
  def okCovering(qs: QuestionSet): HttpResponse = {
    val answers = qs.names.map(n => s""""$n":{"type":"noul","noul":0.5}""").mkString(",")
    HttpResponse(200, Nil, s"""{"model":"test-model","answers":{$answers}}""")
  }

  // poll an observable until it reaches `expected` — bounded, deterministic
  // on the executing scheduler, same pattern as DeterministicClockSuite
  def countReached(observe: CIO[Int], expected: Int): CIO[Unit] = {
    def loop(attemptsLeft: Int): CIO[Unit] =
      observe.flatMap { n =>
        if (n >= expected) CIO.unit
        else if (attemptsLeft <= 0) CIO.fail(new AssertionError(s"expected $expected, saw $n"))
        else CIO.sleep(1.milli).flatMap(_ => loop(attemptsLeft - 1))
      }
    loop(5000)
  }
}
