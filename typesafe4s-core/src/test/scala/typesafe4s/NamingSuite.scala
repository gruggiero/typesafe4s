package typesafe4s

import munit.FunSuite

class NamingSuite extends FunSuite {
  private val lowerSnakeCases = List(
    ("already lowercase", "billing", "billing"),
    ("one uppercase character", "A", "a"),
    ("PascalCase", "TechSupport", "tech_support"),
    ("camelCase", "techSupport", "tech_support"),
    ("acronym followed by a word", "HTTPServer", "h_t_t_p_server"),
    ("all-uppercase acronym", "HTTP", "h_t_t_p"),
    ("digits between words", "Version2Update", "version2_update"),
    ("leading digit before uppercase", "2Factor", "2_factor")
  )

  lowerSnakeCases.foreach { case (description, input, expected) =>
    test(s"lowerSnake: $description") {
      assertEquals(Naming.lowerSnake.label(input), expected)
    }
  }

  test("verbatim preserves mixed case, acronym runs, and digits") {
    val input = "myHTTPServer2"
    assertEquals(Naming.verbatim.label(input), input)
  }
}
