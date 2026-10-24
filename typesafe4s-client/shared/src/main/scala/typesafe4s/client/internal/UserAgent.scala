package typesafe4s.client.internal

import typesafe4s.client.BuildInfo

// ============================================================================
// #21 — every request names the SDK, its version, the backend row and the
// Scala version, so the service can tell typesafe4s traffic apart for
// support and usage questions:
//
//   User-Agent: typesafe4s/0.1.0 (zio; scala/3.8.4)
//
// A caller's own User-Agent (in the configured headers) is kept and the
// SDK's token is appended after it, the RFC 9110 product-list order:
// `myapp/2.3 typesafe4s/0.1.0 (zio; scala/3.8.4)`.
// ============================================================================
private[typesafe4s] object UserAgent {

  val name = "User-Agent"

  // the backend row from the artifact name: "typesafe4s-client-zio" -> "zio"
  private val backend: String = BuildInfo.moduleName.stripPrefix("typesafe4s-client-")

  /**
    * The SDK's product token: `typesafe4s/<version> (<backend>; scala/<version>)`.
    */
  val sdk: String = s"typesafe4s/${BuildInfo.version} ($backend; scala/${BuildInfo.scalaVersion})"

  /**
    * `headers` with one `User-Agent`: the caller's (the last one, as for every
    * header) followed by the SDK's token, or the SDK's token alone when the
    * caller set none or a blank one. Name matching is case-insensitive.
    */
  def withSdk(headers: List[(String, String)]): List[(String, String)] = {
    val caller = headers.reverse.collectFirst { case (n, v) if n.equalsIgnoreCase(name) && !v.isBlank => v.trim }
    headers.filterNot(_._1.equalsIgnoreCase(name)) :+ (name -> caller.fold(sdk)(c => s"$c $sdk"))
  }
}
