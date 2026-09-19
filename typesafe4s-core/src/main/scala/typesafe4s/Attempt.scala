package typesafe4s

// spec: retry-policy — Concepts Introduced: Attempt ("which try this is, from
// which the delay is derived"). The first try is `first` (ordinal 0); the wait
// before the retry following try `a` is derived from `a`.
opaque type Attempt = Int

object Attempt {

  /**
    * The first try of a call.
    */
  val first: Attempt = 0

  /**
    * An attempt by its zero-based ordinal. Requires `ordinal >= 0`.
    */
  def of(ordinal: Int): Attempt = {
    require(ordinal >= 0, "an attempt's ordinal may not be negative")
    ordinal
  }

  extension (a: Attempt) {

    /**
      * The zero-based ordinal this attempt is.
      */
    def ordinal: Int = a

    /**
      * The try after this one. The ordinal may not overflow — an ordinal
      * past the last representable one would silently become negative and
      * derive a wait for a try that cannot exist (Ring 8).
      */
    def next: Attempt = {
      require(a < Int.MaxValue, "an attempt's ordinal may not overflow")
      a + 1
    }
  }
}
