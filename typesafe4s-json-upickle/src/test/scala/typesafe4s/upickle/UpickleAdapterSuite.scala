package typesafe4s.upickle

import munit.ScalaCheckSuite
import org.scalacheck.Prop.{classify, forAll}

import typesafe4s.{AdapterGenerators, Entry, Noul, QuestionSet, SystemOne}
import typesafe4s.AdapterGenerators.*
import typesafe4s.json.Json

// ============================================================================
// TEST ORACLE — spec: json-adapters (change: add-typesafe4s-sdk, spec 9/10)
// Module: typesafe4s-upickle.
//
// Written from the spec BEFORE implementation. Every test cites its spec
// source. The boundary builder `toUjson` converts the core AST into
// ujson.Value (object members are a LinkedHashMap, so member order survives
// the crossing) so the generator stays shared across all four adapters.
// ============================================================================
class UpickleAdapterSuite extends ScalaCheckSuite {

  // core Json -> ujson.Value, lossless
  private def toUjson(j: Json): ujson.Value = j match {
    case Json.JNull       => ujson.Null
    case Json.JBool(b)    => if (b) ujson.True else ujson.False
    case Json.JNumber(n)  => ujson.Num(n.toDouble)
    case Json.JString(s)  => ujson.Str(s)
    case Json.JArray(vs)  => ujson.Arr(vs.map(toUjson)*)
    case Json.JObject(ms) =>
      // ujson.Obj's public constructors are () and (head, rest*) — members
      // are handed over in generated order (ujson stores them insertion-ordered)
      val items = ms.map { case (k, v) => k -> toUjson(v) }
      if (items.isEmpty) ujson.Obj() else ujson.Obj(items.head, items.tail*)
  }

  private val qs = QuestionSet("a" -> Noul(Entry.text("a question")))

  // spec: json-adapters — Property: Conversion of a carryable value is
  // faithful, order included
  property("conversion-is-faithful-order-included") {
    forAll(AdapterGenerators.genEntryJson(3)) { j =>
      classify(true, topShape(j)) {
        classify(nestingDepth(j) >= 2, "nested", "flat") {
          JsonEntry.of(toUjson(j)) match {
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
        JsonEntry.of(toUjson(j)) match {
          case Left(path) => path == planted && memberAt(j, path).exists(isNonCarryable)
          case Right(_)   => false
        }
      }
    }
  }

  // spec: json-adapters — Scenario: Nothing stays nothing
  test("nothing-stays-nothing") {
    assertEquals(JsonEntry.of(ujson.Null).map(_.entry.json), Right(Json.JNull))
  }

  // spec: json-adapters — Scenario: A refused value never reaches the wire.
  // renderRequest IS the wire boundary: the control case proves a carryable
  // value's members land in the request body, so the refusal below shows no
  // JsonEntry exists to hand it — and the compile-negative suite proves no
  // given rescues the raw type.
  test("a-refused-value-reaches-no-wire") {
    val carried = JsonEntry.of(toUjson(Json.JObject(Vector("k" -> Json.JString("v")))))
    assert(
      carried.exists(e => Json.render(SystemOne.renderRequest(e, "jev-latest", qs)).contains("\"k\":\"v\"")),
      "a carryable value did not reach the request body"
    )
    val refused = JsonEntry.of(toUjson(Json.JNumber(42)))
    assert(refused.isLeft, "a number-bearing value was converted")
  }
}
