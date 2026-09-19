package typesafe4s.examples

import ox.supervised

import typesafe4s.{ChoiceAnswer, TypesafeClient}

// spec: release-readiness — Scenario: A confidence-gated routing example
// (ox row). The answer's calibrated confidence drives the routing
// decision: high-confidence choices execute, low-confidence ones escalate.
object ConfidenceRouting {

  def main(args: Array[String]): Unit =
    supervised {
      val (config, transport) = Demo.resolve()
      val client              = TypesafeClient.scoped(config, transport)
      val eval                = client.systemOneDynamic(Demo.state)(Demo.questions)
      val decision            = eval.answers.answers.get("route") match {
        case Some(a: ChoiceAnswer[?]) if a.confidence >= 0.8 =>
          s"confident (${a.confidence}) — auto-executing '${a.choice}'"
        case Some(a: ChoiceAnswer[?])                        =>
          s"low confidence (${a.confidence}) — escalating to a human"
        case _                                               => "no routing answer returned — escalating"
      }
      println(decision)
    }
}
