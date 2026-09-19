package typesafe4s

import org.scalacheck.Gen

import typesafe4s.json.Json

// ============================================================================
// Shared generators for spec: json-adapters (change: add-typesafe4s-sdk,
// spec 9/10). Lives in core test sources so every adapter module reuses it
// via test->test — the spec's generator strategies are stated once and each
// adapter suite draws the same shapes.
// ============================================================================
object AdapterGenerators {

  // one generated unit of text — usually a single Char, occasionally a
  // supplementary-plane pair, so render/parse fidelity is exercised across
  // escapes, control characters, and beyond the BMP
  val genTextPiece: Gen[String] = Gen.frequency(
    5 -> Gen.alphaNumChar.map(_.toString),
    3 -> Gen.oneOf('"', '\\', '\n', '\r', '\t').map(_.toString),
    2 -> Gen.choose(0x00.toChar, 0x1f.toChar).map(_.toString),                     // control chars — render must escape
    3 -> Gen.choose(0x80.toChar, 0x2fff.toChar).map(_.toString),                   // non-ASCII BMP
    2 -> Gen.choose(0x3000.toChar, 0xd7ff.toChar).map(_.toString),                 // BMP above 0x3000 (below surrogates)
    1 -> Gen.choose(0x10000, 0x10fff).map(cp => new String(Character.toChars(cp))) // surrogate pair
  )
  val genText: Gen[String]      = Gen.listOf(genTextPiece).map(_.mkString)

  // member names must be non-empty and free of '.' — the refusal path grammar
  // uses '.' as the segment separator and drops empty segments, so a dotted or
  // empty key would make the named position unresolvable by construction
  val genMemberName: Gen[String] = genText.suchThat(k => k.nonEmpty && !k.contains('.'))

  // object members hold DISTINCT keys — first occurrence wins on a collision
  def distinctKeys[A](ms: List[(String, A)]): List[(String, A)] =
    ms.foldLeft(List.empty[(String, A)]) { (acc, m) =>
      if (acc.exists(_._1 == m._1)) acc else acc :+ m
    }

  // genEntryJson — entry-shaped values only (text/object/array/nothing), so
  // conversion must always succeed: a Left means the ADAPTER is broken.
  // `keyGen` lets genViolatingJson restrict member names to path-safe keys.
  def genEntryJson(depth: Int, keyGen: Gen[String] = genText): Gen[Json] =
    if (depth <= 0) Gen.frequency(1 -> Gen.const(Json.JNull), 4 -> genText.map(Json.JString(_)))
    else
      Gen.frequency(
        4                           -> Gen.frequency(1 -> Gen.const(Json.JNull), 4 -> genText.map(Json.JString(_))),
        1                           -> Gen.choose(0, 3).flatMap(n => Gen.listOfN(n, genEntryJson(depth - 1, keyGen))).map(vs => Json.JArray(vs.toVector)),
        1                           -> Gen
          .choose(0, 3)
          .flatMap(n => Gen.listOfN(n, Gen.zip(keyGen, genEntryJson(depth - 1, keyGen))))
          .map(ms => Json.JObject(distinctKeys(ms).toVector))
      )

  // every position in a Json tree, as $.-paths (the root included)
  def allPaths(j: Json, prefix: String = "$"): List[String] =
    prefix :: (j match {
      case Json.JObject(ms) => ms.toList.flatMap { case (k, v) => allPaths(v, s"$prefix.$k") }
      case Json.JArray(vs)  => vs.toList.zipWithIndex.flatMap { case (v, i) => allPaths(v, s"$prefix.$i") }
      case _                => Nil
    })

  // replaces the subtree at path with leaf
  def replaceAt(j: Json, path: String, leaf: Json): Json = {
    def go(node: Json, segs: List[String]): Json = segs match {
      case Nil         => leaf
      case seg :: rest =>
        node match {
          case Json.JObject(ms) => Json.JObject(ms.map { case (k, v) => if (k == seg) k -> go(v, rest) else (k, v) })
          case Json.JArray(vs)  => Json.JArray(vs.zipWithIndex.map { case (v, i) => if (i.toString == seg) go(v, rest) else v })
          case other            => other
        }
    }
    go(j, path.stripPrefix("$").split("\\.").filter(_.nonEmpty).toList)
  }

  // genViolatingJson — a value that holds exactly one non-carryable member
  // (number or boolean) BY CONSTRUCTION: draws an entry-shaped value over
  // path-safe member names, then plants the violation at a randomly chosen
  // position — the drawn path IS the expected refusal, at any depth.
  def genViolatingJson(depth: Int): Gen[(Json, String)] =
    for {
      base <- genEntryJson(depth, genMemberName)
      leaf <- Gen.oneOf(Gen.oneOf(true, false).map(Json.JBool(_)), Gen.choose(-1e9, 1e9).map(d => Json.JNumber(BigDecimal(d))))
      path <- Gen.oneOf(allPaths(base))
    } yield replaceAt(base, path, leaf) -> path

  // resolves a `$.a.0.b`-style path (Entry.fromJson's format) to the member
  // it names — the oracle checks the named position is genuinely a
  // non-carryable member, so a refusal can't point at thin air
  def memberAt(json: Json, path: String): Option[Json] =
    path.stripPrefix("$").split("\\.").filter(_.nonEmpty).foldLeft(Option(json)) {
      case (Some(Json.JObject(ms)), seg) => ms.collectFirst { case (k, v) if k == seg => v }
      case (Some(Json.JArray(vs)), seg)  => seg.toIntOption.flatMap(vs.lift)
      case _                             => None
    }

  def isNonCarryable(json: Json): Boolean = json match {
    case _: Json.JBool | _: Json.JNumber => true
    case _                               => false
  }

  // classify helpers for the spec's mandated labels: top-level shape and
  // nesting depth for fidelity draws; violation depth for refusal draws
  def topShape(j: Json): String = j match {
    case Json.JNull      => "nothing"
    case _: Json.JString => "text"
    case _: Json.JArray  => "array"
    case _: Json.JObject => "object"
    case _: Json.JBool   => "boolean"
    case _: Json.JNumber => "number"
  }

  def nestingDepth(j: Json): Int = j match {
    case Json.JArray(vs)  => 1 + vs.map(nestingDepth).fold(0)(math.max)
    case Json.JObject(ms) => 1 + ms.map(m => nestingDepth(m._2)).fold(0)(math.max)
    case _                => 0
  }

  def violationDepth(path: String): Int = path.stripPrefix("$").count(_ == '.')
}
