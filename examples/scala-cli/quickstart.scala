//> using scala 3.8.4
//> using jvm 21
//> using dep io.github.gruggiero::typesafe4s-client-zio:0.1.0

// A one-file typesafe4s program for scala-cli (#53):
//
//   scala-cli run examples/scala-cli/quickstart.scala
//
// With TYPESAFE_API_KEY set it asks the live service; without it, it answers
// from a canned response through the real request and response pipeline, so
// it runs anywhere. It depends on the published artifact, not this build.

import typesafe4s.{ApiKey, Entry, HttpRequest, HttpResponse, Noul, RetryPolicy, TypesafeClient}
import typesafe4s.client.{LogLevel, Transport, TypesafeConfig}
import kyo.compat.CIO
import zio.*

import scala.concurrent.duration.DurationInt

object Quickstart extends ZIOAppDefault {

  // a canned System One answer, returned for any request
  val offline: Transport = new Transport {
    def exchange(request: HttpRequest): CIO[HttpResponse] =
      CIO.value(HttpResponse(200, Nil, """{"model":"jev-latest","answers":{"sentiment":{"type":"noul","noul":0.94}},"usage":{"input_tokens":30,"output_tokens":4}}"""))
  }

  val offlineConfig: TypesafeConfig = TypesafeConfig(
    apiKey = ApiKey.of("offline"),
    baseUrl = "http://localhost.invalid",
    model = "jev-latest",
    allowance = 10.seconds,
    retryPolicy = RetryPolicy.of(attempts = 0, totalBound = None),
    headers = Nil,
    logLevel = LogLevel.Error
  )

  def run =
    ZIO.scoped {
      val (config, transport) = TypesafeConfig.resolve().fold(_ => (offlineConfig, offline), (_, Transport.default))
      for {
        client <- TypesafeClient.scoped(config, transport)
        eval   <- client.systemOneDynamic("The battery died after one day.")(
                    List("sentiment" -> Noul(Entry.text("Does this review express frustration?")))
                  )
        _      <- Console.printLine(eval.answers.answers).orDie
      } yield ()
    }
}
