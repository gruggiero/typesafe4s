package typesafe4s.examples

import cats.effect.{IO, IOApp}

import typesafe4s.TypesafeClient

// spec: release-readiness — Scenario: A quickstart per backend (ce row).
// The Cats Effect idiom: the client is a `Resource` — acquired, used and
// released by `use`.
object Quickstart extends IOApp.Simple {

  def run =
    IO(Demo.resolve()).flatMap { case (config, transport) =>
      TypesafeClient.resource(config, transport).use { client =>
        client.systemOneDynamic(Demo.state)(Demo.questions).flatMap { eval =>
          IO.println(Demo.render(eval))
        }
      }
    }
}
