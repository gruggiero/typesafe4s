package typesafe4s

// ============================================================================
// spec: question-batching — Concepts Introduced: Batch (ordered partition).
// `index` is the batch's position in the division; `questions` is a
// QuestionSet, so a batch is non-empty and name-ordered by construction.
// Package-private constructor: batches exist only as `TokenBudget.split`
// output — a caller outside the SDK cannot fabricate a partition out of
// nothing. The generated `copy` can vary an existing batch, but only a
// holder can use it and nothing in the SDK consumes a caller-supplied
// Batch, so it cannot mint a partition that bypasses `split`.
// ============================================================================
final case class Batch private[typesafe4s] (index: Int, questions: QuestionSet)
