package typesafe4s.examples

import ox.supervised

import typesafe4s.TypesafeClient

// spec: release-readiness — Scenario: A quickstart per backend (ox row).
// The Ox idiom: `TypesafeClient.scoped` runs inside `supervised` — the
// client is released when the supervised block ends, success or failure.
object Quickstart {

  def main(args: Array[String]): Unit =
    supervised {
      val (config, transport) = Demo.resolve()
      val client              = TypesafeClient.scoped(config, transport)
      val eval                = client.systemOneDynamic(Demo.state)(Demo.questions)
      println(Demo.render(eval))
    }
}
