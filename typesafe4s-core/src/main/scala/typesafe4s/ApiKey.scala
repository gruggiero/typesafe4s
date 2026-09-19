package typesafe4s

// spec: http-transport — ApiKey operand (order-file mutual-dep #2): the
// request builder needs the authorization value Credential/present
// produces; spec 7 still owns and verifies the full redaction contract and
// environment resolution.
//
// A real (value) class, NOT an opaque type: `opaque type ApiKey = String`
// cannot redact — `key.toString` would compile to String.toString and print
// the secret. A private-ctor class with a private field and a redacting
// toString leaves no string-form path that yields the secret (Credential:
// "render is total").
final class ApiKey private (private val value: String) extends AnyVal {

  /**
    * The credential's only string form — the secret never appears.
    */
  override def toString: String = "<redacted api key>"
}

object ApiKey {

  /**
    * A credential from a supplied value. Blank is refused — that is a
    * presence check, not a format check: the key's shape is MUST-CONFIRM, so
    * nothing about its structure is validated here.
    */
  def of(value: String): ApiKey = {
    require(!value.isBlank, "an API key may not be blank")
    new ApiKey(value)
  }

  // spec: http-transport — Credential/present: the authorization every
  // exchange carries. The raw value is reachable ONLY inside this
  // companion — no accessor is published.
  // spec: client-configuration — `private[typesafe4s]` on the extension:
  // the pair embeds the raw secret, so reading it from outside the SDK is
  // a compile error (a rendering path that yields the secret must not
  // exist — Gate-1 amendment A3).
  extension (key: ApiKey) {

    /**
      * The `Authorization` header pair — `Bearer <key>` — every exchange
      * carries (concepts/credential.md value domains).
      */
    private[typesafe4s] def authorization: (String, String) = "Authorization" -> s"Bearer ${key.value}"

    /**
      * `text` with every occurrence of the credential's value replaced by
      * the redaction marker (spec: client-configuration — rendering is
      * total: config renders, logged header sets, and service content
      * echoed into failures all pass through here).
      */
    private[typesafe4s] def redact(text: String): String =
      text.replace(key.value, "<redacted api key>")
  }
}
