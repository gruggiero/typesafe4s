package typesafe4s.playjson

import munit.ScalaCheckSuite
import org.scalacheck.Prop.{classify, forAll}
import play.api.libs.json.{JsArray, JsBoolean, JsNull, JsNumber, JsObject, JsString, JsValue}

import typesafe4s.{AdapterGenerators, Entry, Noul, QuestionSet, SystemOne}
import typesafe4s.AdapterGenerators.*
import typesafe4s.json.Json

// ============================================================================
// TEST ORACLE — spec: json-adapters (change: add-typesafe4s-sdk, spec 9/10)
// Module: typesafe4s-play-json (#32).
//
// The same oracle every adapter runs. The boundary builder `toJs` converts
// the core AST into a JsValue (JsObject keeps insertion order, so member
// order survives the crossing) so the generator stays shared across all
// adapters.
// ============================================================================
class PlayJsonAdapterSuite extends ScalaCheckSuite {

  // core Json -> JsValue, lossless (JsNumber holds the BigDecimal itself)
  private def toJs(j: Json): JsValue = j match {
    case Json.JNull       => JsNull
    case Json.JBool(b)    => JsBoolean(b)
    case Json.JNumber(n)  => JsNumber(n)
    case Json.JString(s)  => JsString(s)
    case Json.JArray(vs)  => JsArray(vs.map(toJs))
    case Json.JObject(ms) => JsObject(ms.map { case (k, v) => k -> toJs(v) })
  }

  private val qs = QuestionSet("a" -> Noul(Entry.text("a question")))

  // spec: json-adapters — Property: Conversion of a carryable value is
  // faithful, order included
  property("conversion-is-faithful-order-included") {
    forAll(AdapterGenerators.genEntryJson(3)) { j =>
      classify(true, topShape(j)) {
        classify(nestingDepth(j) >= 2, "nested", "flat") {
          JsonEntry.of(toJs(j)) match {
            case Right(e) => Json.render(e.entry.json) == Json.render(j)
            case Left(_)  => false
          }
        }
      }
    }
  }

  // spec: json-adapters — Property: A refusal names a real violation. The
  // planted position is the only violation, so the refusal must name exactly
  // it — memberAt additionally proves the named position is non-carryable.
  property("a-refusal-names-a-real-violation") {
    forAll(AdapterGenerators.genViolatingJson(3)) { case (j, planted) =>
      classify(true, if (violationDepth(planted) == 0) "root" else s"depth-${violationDepth(planted)}") {
        JsonEntry.of(toJs(j)) match {
          case Left(path) => path == planted && memberAt(j, path).exists(isNonCarryable)
          case Right(_)   => false
        }
      }
    }
  }

  // spec: json-adapters — Scenario: Nothing stays nothing
  test("nothing-stays-nothing") {
    assertEquals(JsonEntry.of(JsNull).map(_.entry.json), Right(Json.JNull))
  }

  // spec: json-adapters — Scenario: A refused value never reaches the wire.
  // renderRequest IS the wire boundary: the control case proves a carryable
  // value's members land in the request body, so the refusal below shows no
  // JsonEntry exists to hand it — and the compile-negative suite proves no
  // given rescues the raw type.
  test("a-refused-value-reaches-no-wire") {
    val carried = JsonEntry.of(toJs(Json.JObject(Vector("k" -> Json.JString("v")))))
    assert(
      carried.exists(e => Json.render(SystemOne.renderRequest(e, "jev-latest", qs)).contains("\"k\":\"v\"")),
      "a carryable value did not reach the request body"
    )
    val refused = JsonEntry.of(toJs(Json.JNumber(42)))
    assert(refused.isLeft, "a number-bearing value was converted")
  }
}
