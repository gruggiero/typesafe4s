package typesafe4s

// ============================================================================
// spec: question-batching — Concepts Introduced: TokenEstimate (measure)
// "a conservative, deliberately approximate size for a state and question
// set" — the wrapped Int is a bound the estimate must not understate, never
// a count to trust. The only numeric view is named `approximateTokens`:
// nothing produces a purported exact count (Compile-Negative Obligations).
// ============================================================================
opaque type TokenEstimate = Int

object TokenEstimate {

  private[typesafe4s] def of(tokens: Int): TokenEstimate = tokens

  extension (e: TokenEstimate) {

    def approximateTokens: Int = e

    def within(limit: TokenEstimate): Boolean = e <= limit
  }
}
