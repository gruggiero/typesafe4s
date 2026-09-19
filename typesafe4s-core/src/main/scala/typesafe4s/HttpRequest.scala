package typesafe4s

// spec: http-transport — Concepts Introduced: HttpRequest — "product type,
// data only": method, target, headers, body.
//
// The constructor is private: `exchange` is the only construction path and
// it REQUIRES `key: ApiKey` — "sending a request with no authorization" is
// a compile error, discharged at tier 1 rather than by review. Taking the
// key (not a bare header string) means the sent authorization is
// definitionally what Credential/present produced.
final case class HttpRequest private (
  method: HttpMethod,
  target: String,
  headers: List[(String, String)],
  body: Option[String],
  private val key: ApiKey
) {

  /**
    * `text` with every occurrence of this exchange's credential replaced
    * by the redaction marker (spec: client-configuration — service
    * content echoed into a failure is service content that can carry the
    * key; it is scrubbed before any member stores it).
    */
  private[typesafe4s] def redact(text: String): String = key.redact(text)

  /**
    * The only string form — rendering is total (spec: client-
    * configuration): the authorization value the headers carry is
    * replaced by a redaction marker, and so is any caller-supplied
    * content that happens to contain the secret.
    */
  override def toString: String =
    redact(s"HttpRequest($method,$target,$headers,$body)")
}

object HttpRequest {

  // the names the SDK owns: they always carry the credential's value and
  // the actual body declaration — "an exchange carries what the service
  // requires" is mandatory, so caller headers must not displace them.
  // `equalsIgnoreCase`, never `toLowerCase` — the default locale turns
  // 'I' into 'ı' under tr/az and an owned name would slip through.
  private val ownedNames = Set("authorization", "content-type")

  /**
    * Builds the request an exchange carries: the credential's
    * `Authorization`, the body declaration when a body exists
    * (`Content-Type: application/json` — the wire codec is JSON), and the
    * caller headers.
    *
    * `configured` is overridden by `perCall` name-by-name
    * (case-insensitive; the per-call header wins — Scenario: A per-call
    * header wins over a configured one). The merged list holds each name
    * once, in a stable order: the SDK-owned pair first, then the caller
    * entries — within the caller lists the LAST entry for a name wins, so
    * duplicates cannot reach the wire. Caller entries under
    * `Authorization` or `Content-Type` are dropped — the SDK owns those
    * names. A `Get` request refuses a body outright: the service surface
    * has no body-carrying `Get`, and silently dropping a declared body
    * would put a `Content-Type` on the wire for content never sent.
    */
  def exchange(
    method: HttpMethod,
    target: String,
    body: Option[String],
    key: ApiKey,
    configured: List[(String, String)] = Nil,
    perCall: List[(String, String)] = Nil
  ): HttpRequest = {
    require(
      method != HttpMethod.Get || body.isEmpty,
      "a Get exchange carries no body — use Post for a body-carrying call"
    )
    val caller   = (configured ++ perCall)
      .filterNot(kv => isOwned(kv._1))
      .foldLeft(List.empty[(String, String)]) { (acc, kv) =>
        acc.filterNot(_._1.equalsIgnoreCase(kv._1)) :+ kv
      }
    val required = List(key.authorization) ++ body.toList.map(_ => "Content-Type" -> "application/json")
    new HttpRequest(method, target, required ++ caller, body, key)
  }

  private def isOwned(name: String): Boolean = ownedNames.exists(_.equalsIgnoreCase(name))
}
