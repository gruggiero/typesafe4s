package typesafe4s.examples

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

import typesafe4s.{ChoiceAnswer, TypesafeClient}

// spec: release-readiness — Scenario: A confidence-gated routing example
// (pekko row). The answer's calibrated confidence drives the routing
// decision: high-confidence choices execute, low-confidence ones escalate.
object ConfidenceRouting {

  def main(args: Array[String]): Unit = {
    val (config, transport) = Demo.resolve()
    val done                = TypesafeClient.use(config, transport) { client =>
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
    Await.result(done, 30.seconds)
    ()
  }
}
