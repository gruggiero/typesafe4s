package typesafe4s

// ============================================================================
// spec: question-model — Concepts Introduced: the answer a question yields,
// carried in the question's type so the two cannot disagree. `Question[A]` is
// indexed by its answer (the GADT index); `AnswerOf` projects it, so
// `Tuple.Map[V, AnswerOf]` lifts a tuple of question types to the tuple of
// answer types the named-tuple surface returns.
// ============================================================================
type AnswerOf[Q] = Q match {
  case Question[a] => a
}
