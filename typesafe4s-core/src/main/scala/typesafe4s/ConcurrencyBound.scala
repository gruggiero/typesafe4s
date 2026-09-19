package typesafe4s

import scala.compiletime.error

// ============================================================================
// spec: question-batching — the caller-chosen bound on how many batched
// exchanges may be in flight at once. Positive by construction:
// `apply(inline n)` refuses a non-positive literal in the caller's compile
// (Compile-Negative Obligations); `of` is the runtime path — a dynamic `n`
// cannot reduce the inline check, so it must go through `of`.
// ============================================================================
opaque type ConcurrencyBound = Int

object ConcurrencyBound {

  // the spec names no default; 4 concurrent exchanges is a conservative
  // middle for a batched workload
  val default: ConcurrencyBound = 4

  inline def apply(inline n: Int): ConcurrencyBound =
    inline if n <= 0 then error("a concurrency bound must be positive") else n

  def of(n: Int): Option[ConcurrencyBound] =
    Option.when(n > 0)(n)

  extension (b: ConcurrencyBound) {
    def value: Int = b
  }
}
