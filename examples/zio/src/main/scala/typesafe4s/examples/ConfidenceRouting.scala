package typesafe4s.examples

import zio.*

import typesafe4s.{ChoiceAnswer, TypesafeClient}

// spec: release-readiness — Scenario: A confidence-gated routing example
// (zio row). The answer's calibrated confidence drives the routing
// decision: high-confidence choices execute, low-confidence ones escalate.
object ConfidenceRouting extends ZIOAppDefault {

  def run =
    ZIO.scoped {
      for {
        pair               <- ZIO.succeed(Demo.resolve())
        (config, transport) = pair
        client             <- TypesafeClient.scoped(config, transport)
        eval               <- client.systemOneDynamic(Demo.state)(Demo.questions)
        decision            = eval.answers.answers.get("route") match {
                                case Some(a: ChoiceAnswer[?]) if a.confidence >= 0.8 =>
                                  s"confident (${a.confidence}) — auto-executing '${a.choice}'"
                                case Some(a: ChoiceAnswer[?])                        =>
                                  s"low confidence (${a.confidence}) — escalating to a human"
                                case _                                               => "no routing answer returned — escalating"
                              }
        _                  <- Console.printLine(decision).orDie
      } yield ()
    }
}
