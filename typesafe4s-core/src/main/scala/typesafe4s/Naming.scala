package typesafe4s

// ============================================================================
// spec: question-model — task 3.7 / design open question 3: the enum-case →
// wire-key naming policy for `Choice.derived`. `verbatim` is the default
// given — the wire contract is never implicit — so `Choice.derived` sends the
// case labels unchanged unless a caller brings a `given Naming` into scope.
// ============================================================================
trait Naming {

  /**
    * Maps an enumeration case label to the option key sent on the wire.
    */
  def label(enumCase: String): String
}

object Naming {

  given verbatim: Naming with {
    def label(enumCase: String): String = enumCase
  }

  // camelCase/PascalCase → lower_snake_case: "TechSupport" becomes
  // "tech_support", "Billing" becomes "billing"
  val lowerSnake: Naming = new Naming {
    def label(enumCase: String): String =
      enumCase.zipWithIndex.map { case (c, i) => if (c.isUpper && i > 0) s"_${c.toLower}" else c.toLower.toString }.mkString
  }
}
