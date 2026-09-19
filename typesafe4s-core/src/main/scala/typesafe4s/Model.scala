package typesafe4s

// spec: client-configuration — Evaluation/listJudges element (Concepts
// Introduced, Gate-1 amendment A1): each model the account may ask,
// reported with its description and release date.
final case class Model(name: String, description: String, releaseDate: String)
