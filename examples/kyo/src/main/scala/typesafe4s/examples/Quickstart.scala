package typesafe4s.examples

import kyo.{KyoApp, Scope, Sync}

import typesafe4s.TypesafeClient

// spec: release-readiness — Scenario: A quickstart per backend (kyo row).
// The Kyo idiom: `TypesafeClient.scoped` is pending `Scope` — its release
// runs when the enclosing `Scope.run` finishes, success or failure.
object Quickstart extends KyoApp {

  import kyo.AllowUnsafe.embrace.danger

  run {
    Sync.Unsafe.run {
      Scope.run {
        Sync.defer(Demo.resolve()).flatMap { case (config, transport) =>
          TypesafeClient.scoped(config, transport).flatMap { client =>
            client.systemOneDynamic(Demo.state)(Demo.questions).map { eval =>
              println(Demo.render(eval))
            }
          }
        }
      }
    }
  }
}
