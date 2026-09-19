package typesafe4s.jsoniter

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}

import typesafe4s.{Entry, StateEncoder}
import typesafe4s.json.Json

// ============================================================================
// spec: json-adapters — validated wrapper + total given (mechanism B).
// jsoniter-scala ships no public AST, so the adapter's input is encoded JSON
// — bytes or text, as produced by jsoniter's writers. `of` parses it with the
// core parser and runs the checked Entry.fromJson: a JsonEntry can only ever
// hold a value the API can carry. The given is total because the invariant
// was discharged at construction — the same pattern Entry itself uses.
// ============================================================================
opaque type JsonEntry = Entry

object JsonEntry {

  // Left names the dotted path of the first member that is not entry-shaped,
  // or the decode/parse failure when the input is not JSON at all.
  def of(json: Array[Byte]): Either[String, JsonEntry] =
    decodeUtf8(json).flatMap(of)

  def of(json: String): Either[String, JsonEntry] =
    Json.parse(json) match {
      case Left(error)  => Left(s"position ${error.position}: ${error.detail}")
      case Right(value) => Entry.fromJson(value)
    }

  // strict decoding — bytes that are not valid UTF-8 are a refusal, not a
  // silently mangled document
  private def decodeUtf8(bytes: Array[Byte]): Either[String, String] =
    try
      Right(
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
      )
    catch { case e: CharacterCodingException => Left(s"the input is not UTF-8: ${e.getMessage}") }

  given StateEncoder[JsonEntry] = new StateEncoder[JsonEntry] {
    def encode(value: JsonEntry): Entry = value
  }

  extension (e: JsonEntry) def entry: Entry = e
}
