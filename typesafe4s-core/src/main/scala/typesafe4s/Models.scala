package typesafe4s

import typesafe4s.TypesafeException.ResponseValidation
import typesafe4s.json.Json

// spec: client-configuration — `GET /v1/models` decoding. Strict: a body
// that does not fit the confirmed shape is a ResponseValidation carrying
// a dotted field path — mismatches must surface loudly rather than
// silently.
object Models {

  /**
    * Decodes the listing body: `{"models":[{"name","description",
    * "release_date", ...}]}` → one `Model` per element (name → name,
    * description → description, release_date → releaseDate). CONFIRMED
    * against a live key 2026-09-19 (task 12.3) — the live body carries
    * `{"models":[…]}` with `release_date` as an ISO-8601 timestamp,
    * e.g. `{"name":"jev-latest","description":"…","release_date":
    * "2026-09-10T18:38:01.391457+00:00"}`. (The pre-confirmation shape was
    * `{"data":[{"id","display_name","created_at"}]}` — wrong on every key.)
    */
  private[typesafe4s] def decodeResponse(
    requestId: Option[RequestId],
    body: String
  ): Either[ResponseValidation, List[Model]] =
    Json.parse(body) match {
      case Left(parseError)             =>
        Left(
          ResponseValidation(requestId, "$", s"the body is not JSON (${parseError.detail} at position ${parseError.position})")
        )
      case Right(Json.JObject(members)) =>
        members.collectFirst { case ("models", value) => value } match {
          case None                        =>
            Left(ResponseValidation(requestId, "models", "no 'models' member"))
          case Some(Json.JArray(elements)) =>
            elements.toList.zipWithIndex.foldLeft[Either[ResponseValidation, List[Model]]](Right(Nil)) { case (acc, (element, index)) =>
              for {
                models <- acc
                model  <- readModel(requestId, index, element)
              } yield models :+ model
            }
          case Some(_)                     =>
            Left(ResponseValidation(requestId, "models", "expected an array"))
        }
      case Right(_)                     =>
        Left(ResponseValidation(requestId, "$", "the body is a JSON value, not an object"))
    }

  private def readModel(
    requestId: Option[RequestId],
    index: Int,
    element: Json
  ): Either[ResponseValidation, Model] =
    element match {
      case Json.JObject(members) =>
        def required(name: String): Either[ResponseValidation, Json]                =
          members
            .collectFirst { case (`name`, value) => value }
            .toRight(ResponseValidation(requestId, s"models.$index.$name", s"no '$name' member"))
        def asString(name: String, value: Json): Either[ResponseValidation, String] =
          value match {
            case Json.JString(s) => Right(s)
            case _               =>
              Left(ResponseValidation(requestId, s"models.$index.$name", "expected a string"))
          }
        for {
          name        <- required("name").flatMap(asString("name", _))
          description <- required("description").flatMap(asString("description", _))
          releaseDate <- required("release_date").flatMap(asString("release_date", _))
        } yield Model(name, description, releaseDate)
      case _                     =>
        Left(ResponseValidation(requestId, s"models.$index", "expected an object"))
    }
}
