package typesafe4s.examples

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

import typesafe4s.TypesafeClient

// spec: release-readiness — Scenario: A quickstart per backend (pekko row).
// The Future idiom: `TypesafeClient.use` is the bracket a Future carrier
// can offer — `client.close` runs on every outcome before the result
// completes.
object Quickstart {

  def main(args: Array[String]): Unit = {
    val (config, transport) = Demo.resolve()
    val done                = TypesafeClient.use(config, transport) { client =>
      client.systemOneDynamic(Demo.state)(Demo.questions).map(eval => println(Demo.render(eval)))
    }
    Await.result(done, 30.seconds)
    ()
  }
}
