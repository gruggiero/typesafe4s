package typesafe4s.json

// ============================================================================
// spec: wire-codec — Concepts Introduced: Json
// A JSON value the core can hold. Object members keep the order they were
// given (Vector, not Map). Numbers are BigDecimal — the round-trip identity
// holds without Double's precision loss, and BigDecimal equality is
// scale-insensitive ("1.0" == "1.00").
// ============================================================================
enum Json {
  case JNull
  case JBool(value: Boolean)
  case JNumber(value: BigDecimal)
  case JString(value: String)
  case JArray(values: Vector[Json])
  case JObject(members: Vector[(String, Json)])
}

object Json {

  // spec: wire-codec — Requirement: A rendered value can always be read back
  // Rendering is total: every Json the core can hold renders to text the
  // parser reads back to an equal value, member order included.
  def render(json: Json): String = json match {
    case Json.JNull            => "null"
    case Json.JBool(true)      => "true"
    case Json.JBool(false)     => "false"
    case Json.JNumber(value)   => value.bigDecimal.toString
    case Json.JString(value)   => renderText(value)
    case Json.JArray(values)   => values.map(render).mkString("[", ",", "]")
    case Json.JObject(members) =>
      members.map { case (name, value) => s"${renderText(name)}:${render(value)}" }.mkString("{", ",", "}")
  }

  // the only characters a JSON string may not hold literally: quote, backslash
  // and control characters below 0x20 — everything else renders verbatim
  private def renderText(value: String): String =
    value
      .flatMap {
        case '"'           => "\\\""
        case '\\'          => "\\\\"
        case '\b'          => "\\b"
        case '\f'          => "\\f"
        case '\n'          => "\\n"
        case '\r'          => "\\r"
        case '\t'          => "\\t"
        case c if c < 0x20 => f"\\u${c.toInt}%04x"
        case c             => c.toString
      }
      .mkString("\"", "", "\"")

  // spec: wire-codec — the parser is hand-written; core has no JSON library.
  // Left carries where parsing stopped, for ResponseValidation detail.
  def parse(input: String): Either[Json.ParseError, Json] = {
    val parser = new JsonParser(input)
    parser.skipWhitespace()
    parser.parseValue() match {
      case Left(error)  => Left(error)
      case Right(value) =>
        parser.skipWhitespace()
        if (parser.atEnd) Right(value)
        else Left(Json.ParseError(parser.position, "trailing input after the value"))
    }
  }

  final case class ParseError(position: Int, detail: String)

  // deeper nesting than this is reported as a body that does not fit rather
  // than risking a stack overflow — the bound is far above any real payload
  // yet shallow enough that rejecting the parser's own recursion stays safe on
  // a minimal (~256k) thread stack: each level costs a handful of frames
  private val MaxNesting = 100

  private val numberPattern = java.util.regex.Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

  // a recursive-descent reader over the input; the index is the only mutable
  // state and never escapes — parse/render are referentially transparent
  final private class JsonParser(input: String) {
    private var pos = 0

    def position: Int  = pos
    def atEnd: Boolean = pos >= input.length

    def skipWhitespace(): Unit =
      while (!atEnd && isWhitespace(input.charAt(pos))) pos += 1

    private def isWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    private def isHexDigit(c: Char): Boolean = c.isDigit || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

    private def peek: Char = input.charAt(pos)

    def parseValue(): Either[Json.ParseError, Json] = parseValueAt(0)

    // the nesting bound keeps a crafted deep body a rejected body rather than
    // a stack overflow escaping the failure channel — reading never raises
    private def parseValueAt(depth: Int): Either[Json.ParseError, Json] =
      if (depth > MaxNesting) Left(Json.ParseError(pos, s"the value is nested deeper than $MaxNesting levels"))
      else if (atEnd) Left(Json.ParseError(pos, "unexpected end of input"))
      else
        peek match {
          case '{'                        => parseObject(depth + 1)
          case '['                        => parseArray(depth + 1)
          case '"'                        => parseString().map(Json.JString(_))
          case 't'                        => parseLiteral("true", Json.JBool(true))
          case 'f'                        => parseLiteral("false", Json.JBool(false))
          case 'n'                        => parseLiteral("null", Json.JNull)
          case c if c == '-' || c.isDigit => parseNumber()
          case c                          => Left(Json.ParseError(pos, s"unexpected character '$c'"))
        }

    private def parseLiteral(text: String, value: Json): Either[Json.ParseError, Json] =
      if (input.startsWith(text, pos)) {
        pos += text.length
        Right(value)
      } else Left(Json.ParseError(pos, s"expected '$text'"))

    private def parseNumber(): Either[Json.ParseError, Json] = {
      val matcher = numberPattern.matcher(input)
      val _       = matcher.region(pos, input.length)
      if (matcher.lookingAt()) {
        val text = matcher.group()
        pos = matcher.end()
        try Right(Json.JNumber(BigDecimal(text)))
        catch { case _: NumberFormatException => Left(Json.ParseError(pos, s"'$text' is not a representable number")) }
      } else Left(Json.ParseError(pos, "expected a number"))
    }

    private def parseString(): Either[Json.ParseError, String] = {
      pos += 1 // consume the opening quote
      val out = new StringBuilder

      def loop(): Either[Json.ParseError, String] =
        if (atEnd) Left(Json.ParseError(pos, "the string is not closed"))
        else
          peek match {
            case '"'           =>
              pos += 1
              Right(out.result())
            case '\\'          =>
              pos += 1
              parseEscape() match {
                case Left(error) => Left(error)
                case Right(c)    =>
                  val _ = out.append(c)
                  loop()
              }
            case c if c < 0x20 => Left(Json.ParseError(pos, "a control character must be escaped"))
            case c             =>
              val _ = out.append(c)
              pos += 1
              loop()
          }

      loop()
    }

    private def parseEscape(): Either[Json.ParseError, Char] =
      if (atEnd) Left(Json.ParseError(pos, "the escape sequence is not closed"))
      else
        peek match {
          case '"'  =>
            pos += 1
            Right('"')
          case '\\' =>
            pos += 1
            Right('\\')
          case '/'  =>
            pos += 1
            Right('/')
          case 'b'  =>
            pos += 1
            Right('\b')
          case 'f'  =>
            pos += 1
            Right('\f')
          case 'n'  =>
            pos += 1
            Right('\n')
          case 'r'  =>
            pos += 1
            Right('\r')
          case 't'  =>
            pos += 1
            Right('\t')
          case 'u'  =>
            pos += 1
            if (pos + 4 <= input.length && input.substring(pos, pos + 4).forall(isHexDigit)) {
              val c = Integer.parseInt(input.substring(pos, pos + 4), 16).toChar
              pos += 4
              Right(c)
            } else Left(Json.ParseError(pos, "\\u needs four hexadecimal digits"))
          case c    => Left(Json.ParseError(pos, s"'\\$c' is not a valid escape"))
        }

    private def parseArray(depth: Int): Either[Json.ParseError, Json] = {
      pos += 1 // consume '['
      skipWhitespace()
      if (!atEnd && peek == ']') {
        pos += 1
        Right(Json.JArray(Vector.empty))
      } else {
        def loop(acc: Vector[Json]): Either[Json.ParseError, Json] = {
          skipWhitespace()
          parseValueAt(depth) match {
            case Left(error)  => Left(error)
            case Right(value) =>
              skipWhitespace()
              if (atEnd) Left(Json.ParseError(pos, "the array is not closed"))
              else
                peek match {
                  case ',' =>
                    pos += 1
                    loop(acc :+ value)
                  case ']' =>
                    pos += 1
                    Right(Json.JArray(acc :+ value))
                  case c   => Left(Json.ParseError(pos, s"expected ',' or ']' but found '$c'"))
                }
          }
        }
        loop(Vector.empty)
      }
    }

    private def parseObject(depth: Int): Either[Json.ParseError, Json] = {
      pos += 1 // consume '{'
      skipWhitespace()
      if (!atEnd && peek == '}') {
        pos += 1
        Right(Json.JObject(Vector.empty))
      } else {
        def loop(acc: Vector[(String, Json)]): Either[Json.ParseError, Json] = {
          skipWhitespace()
          if (atEnd || peek != '"') Left(Json.ParseError(pos, "an object member must start with a string"))
          else
            parseString() match {
              case Left(error) => Left(error)
              case Right(name) =>
                skipWhitespace()
                if (atEnd || peek != ':') Left(Json.ParseError(pos, "expected ':' after the member name"))
                else {
                  pos += 1
                  skipWhitespace()
                  parseValueAt(depth) match {
                    case Left(error)  => Left(error)
                    case Right(value) =>
                      skipWhitespace()
                      if (atEnd) Left(Json.ParseError(pos, "the object is not closed"))
                      else
                        peek match {
                          case ',' =>
                            pos += 1
                            loop(acc :+ (name -> value))
                          case '}' =>
                            pos += 1
                            Right(Json.JObject(acc :+ (name -> value)))
                          case c   => Left(Json.ParseError(pos, s"expected ',' or '}' but found '$c'"))
                        }
                  }
                }
            }
        }
        loop(Vector.empty)
      }
    }
  }
}
