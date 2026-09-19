package typesafe4s.examples

import kyo.{KyoApp, Scope, Sync}

import typesafe4s.{ChoiceAnswer, TypesafeClient}

// spec: release-readiness — Scenario: A confidence-gated routing example
// (kyo row). The answer's calibrated confidence drives the routing
// decision: high-confidence choices execute, low-confidence ones escalate.
object ConfidenceRouting extends KyoApp {

  import kyo.AllowUnsafe.embrace.danger

  run {
    Sync.Unsafe.run {
      Scope.run {
        Sync.defer(Demo.resolve()).flatMap { case (config, transport) =>
          TypesafeClient.scoped(config, transport).flatMap { client =>
            client.systemOneDynamic(Demo.state)(Demo.questions).map { eval =>
              val decision = eval.answers.answers.get("route") match {
                case Some(a: ChoiceAnswer[?]) if a.confidence >= 0.8 =>
                  s"confident (${a.confidence}) — auto-executing '${a.choice}'"
                case Some(a: ChoiceAnswer[?])                        =>
                  s"low confidence (${a.confidence}) — escalating to a human"
                case _                                               => "no routing answer returned — escalating"
              }
              println(decision)
            }
          }
        }
      }
    }
  }
}
