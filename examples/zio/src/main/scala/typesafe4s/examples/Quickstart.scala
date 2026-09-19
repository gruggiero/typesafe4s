package typesafe4s.examples

import zio.*

import typesafe4s.TypesafeClient

// spec: release-readiness — Scenario: A quickstart per backend (zio row).
// The ZIO idiom: the client is held by `Scope` and released when the scope
// ends; failures arrive in the typed `TypesafeException` channel.
object Quickstart extends ZIOAppDefault {

  def run =
    ZIO.scoped {
      for {
        pair               <- ZIO.succeed(Demo.resolve())
        (config, transport) = pair
        client             <- TypesafeClient.scoped(config, transport)
        eval               <- client.systemOneDynamic(Demo.state)(Demo.questions)
        _                  <- Console.printLine(Demo.render(eval)).orDie
      } yield ()
    }
}
