package typesafe4s.examples

import cats.effect.{IO, IOApp}

import typesafe4s.{ChoiceAnswer, TypesafeClient}

// spec: release-readiness — Scenario: A confidence-gated routing example
// (ce row). The answer's calibrated confidence drives the routing
// decision: high-confidence choices execute, low-confidence ones escalate.
object ConfidenceRouting extends IOApp.Simple {

  def run =
    IO(Demo.resolve()).flatMap { case (config, transport) =>
      TypesafeClient.resource(config, transport).use { client =>
        client.systemOneDynamic(Demo.state)(Demo.questions).flatMap { eval =>
          val decision = eval.answers.answers.get("route") match {
            case Some(a: ChoiceAnswer[?]) if a.confidence >= 0.8 =>
              s"confident (${a.confidence}) — auto-executing '${a.choice}'"
            case Some(a: ChoiceAnswer[?])                        =>
              s"low confidence (${a.confidence}) — escalating to a human"
            case _                                               => "no routing answer returned — escalating"
          }
          IO.println(decision)
        }
      }
    }
}
