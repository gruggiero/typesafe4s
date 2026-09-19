package typesafe4s.jsoniter

import java.nio.charset.StandardCharsets

import munit.ScalaCheckSuite
import org.scalacheck.Prop.{classify, forAll}

import typesafe4s.{AdapterGenerators, Entry, Noul, QuestionSet, SystemOne}
import typesafe4s.AdapterGenerators.*
import typesafe4s.json.Json

// ============================================================================
// TEST ORACLE — spec: json-adapters (change: add-typesafe4s-sdk, spec 9/10)
// Module: typesafe4s-jsoniter.
//
// Written from the spec BEFORE implementation. jsoniter-scala ships no
// public AST, so the adapter's input is encoded JSON — the generator draws
// core values and hands the adapter their rendered bytes/text, exactly the
// shape a jsoniter caller produces with `writeToArray`.
// ============================================================================
class JsoniterAdapterSuite extends ScalaCheckSuite {

  private val qs = QuestionSet("a" -> Noul(Entry.text("a question")))

  // spec: json-adapters — Property: Conversion of a carryable value is
  // faithful, order included
  property("conversion-is-faithful-order-included") {
    forAll(AdapterGenerators.genEntryJson(3)) { j =>
      val encoded = Json.render(j)
      classify(true, topShape(j)) {
        classify(nestingDepth(j) >= 2, "nested", "flat") {
          JsonEntry.of(encoded) match {
            case Right(e) => Json.render(e.entry.json) == encoded
            case Left(_)  => false
          }
        }
      }
    }
  }

  property("conversion-is-faithful-from-bytes") {
    forAll(AdapterGenerators.genEntryJson(3)) { j =>
      val encoded = Json.render(j).getBytes(StandardCharsets.UTF_8)
      JsonEntry.of(encoded) match {
        case Right(e) => Json.render(e.entry.json) == Json.render(j)
        case Left(_)  => false
      }
    }
  }

  // spec: json-adapters — Property: A refusal names a real violation. The
  // planted position is the only violation, so the refusal must name exactly
  // it — memberAt additionally proves the named position is non-carryable.
  property("a-refusal-names-a-real-violation") {
    forAll(AdapterGenerators.genViolatingJson(3)) { case (j, planted) =>
      classify(true, if (violationDepth(planted) == 0) "root" else s"depth-${violationDepth(planted)}") {
        JsonEntry.of(Json.render(j)) match {
          case Left(path) => path == planted && memberAt(j, path).exists(isNonCarryable)
          case Right(_)   => false
        }
      }
    }
  }

  // spec: json-adapters — the same refusal for input that is not JSON at all:
  // Left names the parse failure rather than a member path
  test("non-json-input-is-refused-naming-the-parse-failure") {
    JsonEntry.of("{ not json :::") match {
      case Left(msg) => assert(msg.nonEmpty, "the refusal names nothing")
      case Right(_)  => fail("non-JSON input was converted")
    }
  }

  // spec: json-adapters — Scenario: Nothing stays nothing
  test("nothing-stays-nothing") {
    assertEquals(JsonEntry.of("null").map(_.entry.json), Right(Json.JNull))
  }

  // spec: json-adapters — Scenario: A refused value never reaches the wire.
  // renderRequest IS the wire boundary: the control case proves a carryable
  // value's members land in the request body, so the refusal below shows no
  // JsonEntry exists to hand it — and the compile-negative suite proves no
  // given rescues the raw type.
  test("a-refused-value-reaches-no-wire") {
    val carried = JsonEntry.of("""{"k":"v"}""")
    assert(
      carried.exists(e => Json.render(SystemOne.renderRequest(e, "jev-latest", qs)).contains("\"k\":\"v\"")),
      "a carryable value did not reach the request body"
    )
    val refused = JsonEntry.of("""{"count": 42}""")
    assert(refused.isLeft, "a number-bearing value was converted")
  }
}
