package typesafe4s

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.{classify, forAll}

// ============================================================================
// TEST ORACLE (core half) — spec: http-transport (change: add-typesafe4s-sdk,
// spec 5/8)
//
// The pure surface — the ApiKey operand (Credential/present), request
// construction and its header precedence — is exercised here in core. The
// exchange itself (timeout, abandonment, retry) needs the carrier and lives
// in typesafe4s-client/shared — see internal/TransportProperties there.
//
// Generators are constructive — ScalaCheck's classify is informational only,
// so interesting cases are reached by construction, never by rare draws.
// ============================================================================
final class HttpTransportProperties extends ScalaCheckSuite {

  // --------------------------------------------------------------------------
  // Generators
  // --------------------------------------------------------------------------

  // spec: http-transport — generator strategy `genHeaderSetup`: configured and
  // per-call header maps drawn from a SHARED small key alphabet, so overlap
  // happens on most runs rather than rarely — overlap is the case the
  // requirement is about. Includes the empty map on both sides. The two names
  // the SDK owns (authorization, content-type) are NOT in the alphabet — the
  // contract gives them dedicated scenarios (T5).
  private val headerAlphabet = Vector("x-tenant", "x-trace", "x-tag", "x-req-id", "x-extra")

  private val genHeaderMap: Gen[Map[String, String]] =
    Gen
      .choose(0, 4)
      .flatMap(n => Gen.mapOfN(n, Gen.zip(Gen.oneOf(headerAlphabet), Gen.oneOf("alpha", "beta", "gamma", "42"))))

  private val genApiKey: Gen[ApiKey] =
    Gen.nonEmptyListOf(Gen.alphaNumChar).map(cs => ApiKey.of(cs.mkString))

  private val genHeaderSetup: Gen[(List[(String, String)], List[(String, String)], ApiKey)] =
    for {
      configured <- genHeaderMap.map(_.toList)
      perCall    <- genHeaderMap.map(_.toList)
      key        <- genApiKey
    } yield (configured, perCall, key)

  private val reserved = Set("authorization", "content-type")

  /**
    * The whole sent header set as a lowercase map — one entry per name.
    * ROOT locale: the oracle normalizes independently of the ambient
    * locale, since one scenario deliberately runs under tr_TR.
    */
  private def sentMap(headers: List[(String, String)]): Map[String, String] =
    headers.map { case (n, v) => n.toLowerCase(java.util.Locale.ROOT) -> v }.toMap

  // --------------------------------------------------------------------------
  // spec: http-transport — Property: Every request carries its required headers
  // --------------------------------------------------------------------------
  property("every-request-carries-required-headers") {
    forAll(genHeaderSetup) { case (configured, perCall, key) =>
      val request  = HttpRequest.exchange(HttpMethod.Post, "/v1/systemone", Some("""{"subject":"s"}"""), key, configured, perCall)
      val sent     = request.headers
      // the model: configured overridden by perCall for every name, then the
      // SDK-owned authorization (what Credential/present produced) and the
      // body declaration — asserted as ONE exact map, not a sampling.
      val caller   = perCall
        .foldLeft(configured.map { case (n, v) => n.toLowerCase(java.util.Locale.ROOT) -> v }.toMap)((m, kv) =>
          m + (kv._1.toLowerCase(java.util.Locale.ROOT) -> kv._2)
        )
        .filterNot { case (n, _) => reserved(n) }
      val expected = caller ++ Map("authorization" -> key.authorization._2, "content-type" -> "application/json")
      val overlap  =
        configured.map(_._1.toLowerCase(java.util.Locale.ROOT)).toSet.intersect(perCall.map(_._1.toLowerCase(java.util.Locale.ROOT)).toSet)
      classify(overlap.nonEmpty, "overlapping names", "disjoint") {
        classify(configured.isEmpty, "configured empty") {
          classify(perCall.isEmpty, "per-call empty") {
            sentMap(sent) == expected && sent.size == expected.size
          }
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // Scenarios — one test per spec heading
  // --------------------------------------------------------------------------

  // spec: http-transport — Scenario: An exchange carries what the service requires
  test("an exchange carries what the service requires") {
    val key = ApiKey.of("sk-test-1")
    val req = HttpRequest.exchange(
      HttpMethod.Post,
      "/v1/systemone",
      Some("""{"subject":"s"}"""),
      key,
      configured = List("x-tenant" -> "t1")
    )
    assert(req.headers.contains(key.authorization), s"the credential's authorization is missing: ${req.headers}")
    assert(
      req.headers.contains("Content-Type" -> "application/json"),
      s"the body declaration is missing: ${req.headers}"
    )
    assert(req.headers.contains("x-tenant" -> "t1"), s"a configured header is missing: ${req.headers}")
  }

  // spec: http-transport — Scenario: A per-call header wins over a configured one
  test("a per-call header wins over a configured one") {
    val key = ApiKey.of("sk-test-2")
    val req = HttpRequest.exchange(
      HttpMethod.Post,
      "/v1/systemone",
      Some("{}"),
      key,
      configured = List("x-tenant" -> "configured"),
      perCall = List("x-tenant" -> "per-call")
    )
    assertEquals(sentMap(req.headers)("x-tenant"), "per-call")
    assertEquals(req.headers.count(_._1.equalsIgnoreCase("x-tenant")), 1, "the name was sent twice")
  }

  // spec: http-transport — contract T5: the SDK-owned names cannot be displaced
  // — "an exchange carries what the service requires" is mandatory.
  test("caller headers cannot displace the credential or the body declaration") {
    val key = ApiKey.of("sk-test-3")
    val req = HttpRequest.exchange(
      HttpMethod.Post,
      "/v1/systemone",
      Some("{}"),
      key,
      configured = List("Authorization" -> "Bearer wrong", "Content-Type" -> "text/plain"),
      perCall = List("authorization" -> "Bearer also-wrong")
    )
    assertEquals(sentMap(req.headers)("authorization"), key.authorization._2)
    assertEquals(sentMap(req.headers)("content-type"), "application/json")
    assertEquals(req.headers.count(_._1.equalsIgnoreCase("authorization")), 1, "authorization was sent twice")
  }

  // spec: http-transport — contract T5 holds under any default locale:
  // `AUTHORIZATION` under tr/az lowercases to `authorızatıon`, so a
  // locale-sensitive ownership check would let a caller displace the
  // credential. The check must be locale-independent.
  test("caller headers cannot displace the credential under the Turkish locale") {
    val key      = ApiKey.of("sk-test-locale")
    val previous = java.util.Locale.getDefault
    try {
      java.util.Locale.setDefault(java.util.Locale.of("tr", "TR"))
      val req = HttpRequest.exchange(
        HttpMethod.Post,
        "/v1/systemone",
        Some("{}"),
        key,
        configured = List("AUTHORIZATION" -> "Bearer wrong"),
        perCall = List("CONTENT-TYPE" -> "text/plain")
      )
      assertEquals(sentMap(req.headers)("authorization"), key.authorization._2)
      assertEquals(sentMap(req.headers)("content-type"), "application/json")
      assertEquals(req.headers.count(_._1.equalsIgnoreCase("authorization")), 1)
    } finally java.util.Locale.setDefault(previous)
  }

  // spec: http-transport — contract T5: "the merged list holds each name
  // once" — duplicate names WITHIN `configured` must collapse too, or the
  // wire carries the name twice. The Map-based generator cannot draw this;
  // it needs a list with a repeated name, so it is a dedicated scenario.
  // Last entry wins, matching per-call-over-configured precedence.
  test("duplicate names within the configured headers collapse to the last entry") {
    val key = ApiKey.of("sk-test-dup")
    val req = HttpRequest.exchange(
      HttpMethod.Post,
      "/v1/systemone",
      Some("{}"),
      key,
      configured = List("x-a" -> "first", "x-b" -> "kept", "X-A" -> "second")
    )
    assertEquals(sentMap(req.headers)("x-a"), "second")
    assertEquals(req.headers.count(_._1.equalsIgnoreCase("x-a")), 1, "the name was sent twice")
    assertEquals(sentMap(req.headers)("x-b"), "kept")
  }

  // spec: http-transport — a Get exchange carries no body: the service
  // surface has no body-carrying Get, and constructing one would silently
  // drop the declared body on the wire (the JDK builder's GET sends none)
  // while still emitting Content-Type for it — a request that lies about
  // itself is refused at construction.
  test("a Get exchange refuses a body") {
    val key = ApiKey.of("sk-test-getbody")
    intercept[IllegalArgumentException] {
      HttpRequest.exchange(HttpMethod.Get, "/v1/models", Some("{}"), key)
    }
    // and a bodiless Get remains constructible — the refusal is the body,
    // not the method
    HttpRequest.exchange(HttpMethod.Get, "/v1/models", None, key)
  }

  // spec: http-transport — contract T6: a bodiless request declares no body
  test("a request without a body declares none") {
    val key = ApiKey.of("sk-test-4")
    val req = HttpRequest.exchange(HttpMethod.Get, "/v1/models", None, key, configured = List("Content-Type" -> "text/plain"))
    assert(req.headers.forall(!_._1.equalsIgnoreCase("content-type")), s"a bodiless request declared a body: ${req.headers}")
    assert(req.headers.contains(key.authorization))
  }

  // spec: http-transport — ApiKey operand (T3): blank is refused — presence,
  // not shape
  test("a blank credential is refused") {
    intercept[IllegalArgumentException](ApiKey.of(""))
    intercept[IllegalArgumentException](ApiKey.of("   "))
  }

  // spec: http-transport — ApiKey operand (T1): no string form yields the
  // secret — the operand lands already shaped for spec 7's redaction contract
  test("the credential never reveals its secret in a string form") {
    val key = ApiKey.of("sk-live-secret")
    assert(!key.toString.contains("sk-live-secret"), s"toString leaked the secret: ${key.toString}")
    assert(!s"$key".contains("sk-live-secret"), s"interpolation leaked the secret: $key")
    assert(!s"$key suffix".contains("sk-live-secret"), "an embedded interpolation leaked the secret")
  }
}
