package typesafe4s.client

import scala.annotation.unused
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.util.{Failure, Try}

import kyo.compat.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.{classify, forAll}

import typesafe4s.{ApiKey, Entry, HttpMethod, HttpRequest, HttpResponse, Noul, RequestId, RetryPolicy, TypesafeException}
import typesafe4s.TypesafeException.*
import typesafe4s.client.internal.StandInTransport
import typesafe4s.client.internal.StandInTransport.Step

// ============================================================================
// TEST ORACLE — spec: client-configuration (change: add-typesafe4s-sdk, 7/8)
//
// Written from the spec and the Gate-1-approved contract BEFORE any
// implementation. Compiles into every backend row (`sbt testUnit` runs it
// five times — the parity table's "resolution order" and "secret never
// rendered" rows are therefore asserted per artifact).
// ============================================================================
final class ClientConfigurationProperties extends ScalaCheckSuite {

  // the ox binding's `unsafeRun` takes an ExecutionContext; the others do
  // not — supplied so every row compiles under -Werror -Wunused.
  @unused private given ExecutionContext = munitExecutionContext

  private def run[A](c: CIO[A]): Try[A] = Try(Await.result(c.unsafeRun, 30.seconds))

  // ==========================================================================
  // spec: client-configuration — Requirement: A setting is taken from the
  // argument, then the environment, then the default
  // ==========================================================================

  // --------------------------------------------------------------------------
  // generator strategy `genResolution` (constructive): each setting draws
  // present-or-absent independently for the argument and the environment, so
  // all four combinations arise on a short run; the alphabet includes the
  // empty string, so "present but empty" is reached by construction.
  // `classify` labels: the present/absent combination, per setting.
  // --------------------------------------------------------------------------

  private val genValue: Gen[String] =
    Gen.oneOf("", "x", "jev", "api.typesafe.ai", "custom.example", "v 1")

  private val genLevel: Gen[String] =
    Gen.oneOf("debug", "info", "warn", "warning", "error", "DEBUG", "Info", "bogus", "")

  final private case class Drawn(
    argKey: Option[String],
    envKey: Option[String],
    argUrl: Option[String],
    envUrl: Option[String],
    argModel: Option[String],
    envModel: Option[String],
    argLevel: Option[String],
    envLevel: Option[String],
    decoys: Map[String, String]
  )

  private val genResolution: Gen[Drawn] =
    for {
      argKey   <- Gen.option(genValue)
      envKey   <- Gen.option(genValue)
      argUrl   <- Gen.option(genValue)
      envUrl   <- Gen.option(genValue)
      argModel <- Gen.option(genValue)
      envModel <- Gen.option(genValue)
      argLevel <- Gen.option(genLevel)
      envLevel <- Gen.option(genLevel)
      decoys   <- Gen.mapOf(Gen.zip(Gen.oneOf("TYPESAFE_SECRET", "OTHER_VAR", "TYPESAFE_TOKEN", "API_KEY"), genValue))
    } yield Drawn(argKey, envKey, argUrl, envUrl, argModel, envModel, argLevel, envLevel, decoys)

  // the INDEPENDENT model of the documented parse — NOT `LogLevel.parse`,
  // which is the code under test
  private def expectedLevel(text: String): Option[LogLevel] =
    text.trim.toLowerCase match {
      case "debug"            => Some(LogLevel.Debug)
      case "info"             => Some(LogLevel.Info)
      case "warn" | "warning" => Some(LogLevel.Warning)
      case "error"            => Some(LogLevel.Error)
      case _                  => None
    }

  // the INDEPENDENT model of resolution: a blank credential is ABSENT (it
  // can never construct an ApiKey); other settings are verbatim orElse —
  // present-but-empty is a value. Credential resolves first: a missing one
  // reports MissingCredential even when another setting is also invalid.
  private def expected(d: Drawn): Either[TypesafeException, (String, LogLevel)] = {
    val keyE   = d.argKey
      .filter(!_.isBlank)
      .orElse(d.envKey.filter(!_.isBlank))
      .toRight(MissingCredential(TypesafeConfig.apiKeyEnv): TypesafeException)
    val levelE = d.argLevel.orElse(d.envLevel) match {
      case None       => Right(TypesafeConfig.defaultLogLevel)
      case Some(text) =>
        expectedLevel(text).toRight(InvalidConfiguration(TypesafeConfig.logLevelEnv, "unparseable"): TypesafeException)
    }
    for {
      key   <- keyE
      level <- levelE
    } yield key -> level
  }

  // spec: client-configuration — Property: Resolution follows one order for
  // every setting. The expected value is computed from the drawn structure
  // by an independent model, never by calling the implementation.
  property("resolution-follows-one-order") {
    forAll(genResolution) { d =>
      val consulted                             = scala.collection.mutable.Set.empty[String]
      val env                                   = Map(
        TypesafeConfig.apiKeyEnv   -> d.envKey,
        TypesafeConfig.baseUrlEnv  -> d.envUrl,
        TypesafeConfig.modelEnv    -> d.envModel,
        TypesafeConfig.logLevelEnv -> d.envLevel
      ) ++ d.decoys.map(kv => kv._1 -> Some(kv._2))
      val environment: String => Option[String] = name => { consulted += name; env.get(name).flatten }
      val result                                = TypesafeConfig.resolve(
        apiKey = d.argKey,
        baseUrl = d.argUrl,
        model = d.argModel,
        logLevel = d.argLevel,
        environment = environment
      )
      val documented                            = Set(
        TypesafeConfig.apiKeyEnv,
        TypesafeConfig.baseUrlEnv,
        TypesafeConfig.modelEnv,
        TypesafeConfig.logLevelEnv
      )
      classify(d.argKey.isDefined, "key arg present", "key arg absent") {
        classify(d.envKey.isDefined, "key env present", "key env absent") {
          val onlyDocumented = consulted.subsetOf(documented)
          (result, expected(d)) match {
            case (Right(cfg), Right((key, level))) =>
              onlyDocumented &&
                cfg.apiKey.authorization == ("Authorization" -> s"Bearer $key") &&
                cfg.baseUrl == d.argUrl.orElse(d.envUrl).getOrElse(TypesafeConfig.defaultBaseUrl) &&
                cfg.model == d.argModel.orElse(d.envModel).getOrElse(TypesafeConfig.defaultModel) &&
                cfg.logLevel == level &&
                cfg.allowance == TypesafeConfig.defaultAllowance
            case (Left(a), Left(e))                =>
              // compared by member + named variable, never by message text
              (a, e) match {
                case (MissingCredential(v1), MissingCredential(v2))             => onlyDocumented && v1 == v2
                case (InvalidConfiguration(v1, _), InvalidConfiguration(v2, _)) => onlyDocumented && v1 == v2
                case _                                                          => false
              }
            case _                                 => false
          }
        }
      }
    }
  }

  // spec: client-configuration — Scenario: Everything from the environment
  test("everything-from-the-environment") {
    val env: String => Option[String] = Map(TypesafeConfig.apiKeyEnv -> "k-env").get
    TypesafeConfig.resolve(environment = env) match {
      case Right(cfg) =>
        assertEquals(cfg.apiKey.authorization, "Authorization" -> "Bearer k-env")
        assertEquals(cfg.baseUrl, TypesafeConfig.defaultBaseUrl)
        assertEquals(cfg.model, TypesafeConfig.defaultModel)
        assertEquals(cfg.logLevel, TypesafeConfig.defaultLogLevel)
        assertEquals(cfg.allowance, TypesafeConfig.defaultAllowance)
      case Left(e)    => fail(s"expected resolution to succeed, got $e")
    }
  }

  // spec: client-configuration — Scenario: An argument beats the environment
  test("an-argument-beats-the-environment") {
    val env: String => Option[String] = Map(
      TypesafeConfig.apiKeyEnv  -> "k-env",
      TypesafeConfig.baseUrlEnv -> "https://env.example",
      TypesafeConfig.modelEnv   -> "env-model"
    ).get
    TypesafeConfig.resolve(
      apiKey = Some("k-arg"),
      baseUrl = Some("https://arg.example"),
      model = Some("arg-model"),
      environment = env
    ) match {
      case Right(cfg) =>
        assertEquals(cfg.apiKey.authorization, "Authorization" -> "Bearer k-arg")
        assertEquals(cfg.baseUrl, "https://arg.example")
        assertEquals(cfg.model, "arg-model")
      case Left(e)    => fail(s"expected resolution to succeed, got $e")
    }
  }

  // spec: client-configuration — Scenario: The documented variables are the
  // ones consulted
  test("the-documented-variables-are-the-ones-consulted") {
    val consulted                     = scala.collection.mutable.Set.empty[String]
    val env: String => Option[String] = name => {
      consulted += name
      Map(
        TypesafeConfig.apiKeyEnv -> "k",
        "TYPESAFE_SECRET"        -> "decoy-1",
        "OTHER_VAR"              -> "decoy-2"
      ).get(name)
    }
    val _                             = TypesafeConfig.resolve(environment = env)
    assert(
      consulted.subsetOf(
        Set(TypesafeConfig.apiKeyEnv, TypesafeConfig.baseUrlEnv, TypesafeConfig.modelEnv, TypesafeConfig.logLevelEnv)
      ),
      s"undocumented variables were consulted: ${consulted.diff(Set(TypesafeConfig.apiKeyEnv, TypesafeConfig.baseUrlEnv, TypesafeConfig.modelEnv, TypesafeConfig.logLevelEnv))}"
    )
    assertEquals(
      consulted.toSet,
      Set(TypesafeConfig.apiKeyEnv, TypesafeConfig.baseUrlEnv, TypesafeConfig.modelEnv, TypesafeConfig.logLevelEnv),
      "with no arguments, every documented variable must be consulted"
    )
  }

  // spec: client-configuration — Proof Obligation: a missing credential
  // fails at construction, naming the variable
  test("a-missing-credential-fails-naming-the-variable") {
    TypesafeConfig.resolve(environment = _ => None) match {
      case Left(MissingCredential(envVar)) =>
        assertEquals(envVar, "TYPESAFE_API_KEY")
      case other                           =>
        fail(s"expected MissingCredential(TYPESAFE_API_KEY), got $other")
    }
  }

  // spec: client-configuration — Gate-1 amendment A2: an environment value
  // that cannot be used fails naming the variable — never a silent default
  test("an-unusable-env-value-fails-naming-the-variable") {
    val env: String => Option[String] = Map(
      TypesafeConfig.apiKeyEnv   -> "k",
      TypesafeConfig.logLevelEnv -> "bogus"
    ).get
    TypesafeConfig.resolve(environment = env) match {
      case Left(InvalidConfiguration(variable, _)) =>
        assertEquals(variable, "TYPESAFE_LOG_LEVEL")
      case other                                   =>
        fail(s"expected InvalidConfiguration(TYPESAFE_LOG_LEVEL), got $other")
    }
  }

  // spec: client-configuration — Ring-8 fix: a failure DETAIL built from an
  // environment value that coincidentally contains the secret's characters
  // must not render them — the detail is scrubbed with the resolved
  // credential before it is stored
  test("a-failure-detail-that-contains-the-secret-is-scrubbed") {
    val env: String => Option[String] = Map(
      TypesafeConfig.apiKeyEnv   -> "zz",
      TypesafeConfig.logLevelEnv -> "zz"
    ).get
    TypesafeConfig.resolve(environment = env) match {
      case Left(e: InvalidConfiguration) =>
        assert(!e.getMessage.contains("zz"), s"failure detail rendered the secret: ${e.getMessage}")
        assert(e.getMessage.contains("TYPESAFE_LOG_LEVEL"), s"the variable was not named: ${e.getMessage}")
      case other                         =>
        fail(s"expected InvalidConfiguration, got $other")
    }
  }

  // spec: client-configuration — Ring-8 coverage: the service echoes the
  // secret in the x-typesafe-request-id header on a NON-refusal status —
  // the InternalServer member stores requestId, body and header values,
  // all scrubbed before storage
  test("a-service-echoed-request-id-does-not-leak-the-secret") {
    val secret  = "sk-echo9"
    val program = for {
      transport <- StandInTransport.init(
                     Nil,
                     Step.Answer(HttpResponse(503, List("x-typesafe-request-id" -> s"rid-$secret"), s"echo: $secret"))
                   )
      client     = Client.cio(configOf(secret, "api.typesafe.ai", "jev-latest", Nil), transport)
      outcome   <- client.systemOneDynamic("subject")(Seq("q" -> noulQ)).liftToTry
    } yield outcome match {
      case Failure(e) =>
        assert(!e.getMessage.contains(secret), s"InternalServer rendered the secret: ${e.getMessage}")
      case other      => fail(s"expected InternalServer, got $other")
    }
    run(program).get
  }

  // ==========================================================================
  // spec: client-configuration — Requirement: A secret is never shown
  // ==========================================================================

  // --------------------------------------------------------------------------
  // generator strategy `genSecretInContext` (constructive): the secret comes
  // from an alphabet that deliberately includes short values and values that
  // are substrings of ordinary settings ("jev" inside "jev-latest"), then is
  // embedded in a drawn context — bare configuration, a configuration inside
  // a larger structure, a logged header set, each member of the failure
  // family (enumerated), and a refusal whose service echo carries the secret
  // (enumerated statuses). `classify` labels: context drawn, secret length.
  // --------------------------------------------------------------------------

  private val genSecret: Gen[String] =
    Gen.oneOf("zz", "q7", "sk-live", "jev", "tk_42", "0x9", "Zz00")

  // ordinary settings share the alphabet, so the secret regularly collides
  // with a non-secret field — the case a naive redactor leaks
  private val genFragment: Gen[String] =
    Gen.oneOf("jev", "jev-latest", "api.typesafe.ai", "plain", "zz", "0x9", "v1")

  private def configOf(secret: String, baseUrl: String, model: String, headers: List[(String, String)]): TypesafeConfig =
    TypesafeConfig(
      apiKey = ApiKey.of(secret),
      baseUrl = baseUrl,
      model = model,
      allowance = 10.seconds,
      retryPolicy = RetryPolicy.of(attempts = 0, totalBound = None),
      headers = headers,
      logLevel = LogLevel.Debug
    )

  final private case class Outer(config: TypesafeConfig)

  private val rid: Option[RequestId] = RequestId.fromHeader("rid-1")

  // the enumerated failure family — every member this SDK can raise, drawn
  // with fixed non-secret details (members cannot hold an ApiKey; the
  // secret-in-detail path is exercised through the pipeline echo below)
  private val familyMembers: List[TypesafeException] = List(
    BadRequest(rid, "malformed"),
    Authentication(rid, "unauthenticated"),
    PermissionDenied(rid, "forbidden"),
    NotFound(rid, "unknown"),
    UnprocessableEntity(rid, "unprocessable"),
    RateLimit(rid, Some(3.seconds), "too many"),
    Overloaded(rid, "overloaded"),
    InternalServer(503, rid, "service fault"),
    ResponseValidation(rid, "answers.q", "wrong shape"),
    ConnectionFailed(new java.io.IOException("refused")),
    Timeout(5.seconds),
    MissingAnswer("q1"),
    InvalidQuestion(Some("q1"), "over the limit"),
    MissingCredential("TYPESAFE_API_KEY"),
    InvalidConfiguration("TYPESAFE_LOG_LEVEL", "not a level")
  )

  private enum RenderContext {
    case BareConfig
    case ConfigInList
    case ConfigInCaseClass
    case LoggedRequest
    case FamilyMember(member: TypesafeException)
    case RefusalEcho(status: Int)
  }

  private val genContext: Gen[RenderContext] = Gen.frequency(
    2 -> Gen.const(RenderContext.BareConfig),
    1 -> Gen.const(RenderContext.ConfigInList),
    1 -> Gen.const(RenderContext.ConfigInCaseClass),
    2 -> Gen.const(RenderContext.LoggedRequest),
    2 -> Gen.oneOf(familyMembers).map(RenderContext.FamilyMember(_)),
    2 -> Gen.oneOf(400, 401, 403, 404, 422, 429, 500, 503, 529).map(RenderContext.RefusalEcho(_))
  )

  private val genSecretInContext: Gen[(String, RenderContext, String, String, List[(String, String)])] =
    for {
      secret  <- genSecret
      ctx     <- genContext
      baseUrl <- genFragment
      model   <- genFragment
      headers <- Gen.listOf(Gen.zip(Gen.oneOf("x-tag", "x-trace", "x-extra"), genFragment))
    } yield (secret, ctx, baseUrl, model, headers)

  private val noulQ = Noul(Entry.text("is it true?"))

  // the render under test, per context — a config's own string form, a
  // larger structure's, a logged request's, a failure's message
  private def rendered(secret: String, ctx: RenderContext, baseUrl: String, model: String, headers: List[(String, String)]): String =
    ctx match {
      case RenderContext.BareConfig        =>
        configOf(secret, baseUrl, model, headers).toString
      case RenderContext.ConfigInList      =>
        List(configOf(secret, baseUrl, model, headers)).toString
      case RenderContext.ConfigInCaseClass =>
        Outer(configOf(secret, baseUrl, model, headers)).toString
      case RenderContext.LoggedRequest     =>
        HttpRequest
          .exchange(HttpMethod.Post, s"https://$baseUrl/v1/systemone", Some("{}"), ApiKey.of(secret), configured = headers)
          .toString
      case RenderContext.FamilyMember(m)   =>
        m.getMessage
      case RenderContext.RefusalEcho(_)    =>
        // filled by the property — the render is the raised failure's message
        ""
    }

  // spec: client-configuration — Property: No rendering of anything ever
  // contains the secret
  property("no-rendering-contains-the-secret") {
    forAll(genSecretInContext) { case (secret, ctx, baseUrl, model, headers) =>
      classify(ctx.isInstanceOf[RenderContext.RefusalEcho], "pipeline echo", "direct render") {
        classify(secret.length <= 2, "short secret", "longer secret") {
          ctx match {
            case RenderContext.RefusalEcho(status) =>
              // the service echoes the key in a refusal body — the raised
              // failure's message must still not carry it
              val body    = s"refused: $secret echoed"
              val program = for {
                transport <- StandInTransport.init(Nil, Step.Answer(HttpResponse(status, Nil, body)))
                client     = Client.cio(configOf(secret, baseUrl, model, headers), transport)
                outcome   <- client.systemOneDynamic("subject")(Seq("q" -> noulQ)).liftToTry
              } yield outcome match {
                case Failure(e) => !e.getMessage.contains(secret)
                case other      => assert(false, s"expected a refusal, got $other"); false
              }
              run(program).getOrElse(false)
            case _                                 =>
              !rendered(secret, ctx, baseUrl, model, headers).contains(secret)
          }
        }
      }
    }
  }

  // spec: client-configuration — Scenario: Rendering a configuration
  test("rendering-a-configuration") {
    val cfg = configOf("sk-render", "https://api.typesafe.ai", "jev-latest", List("x-tag" -> "v1"))
    assert(!cfg.toString.contains("sk-render"), s"config render leaked the secret: ${cfg.toString}")
    assert(cfg.toString.contains("<redacted"), s"no redaction marker in: ${cfg.toString}")
    assert(cfg.toString.contains("https://api.typesafe.ai"), "non-secret baseUrl not readable")
    assert(cfg.toString.contains("jev-latest"), "non-secret model not readable")
  }

  // spec: client-configuration — Scenario: Logging an exchange at the most
  // detailed level — the authorization value is replaced by a marker
  test("logging-an-exchange-at-the-most-detailed-level") {
    val request = HttpRequest.exchange(
      HttpMethod.Post,
      "https://api.typesafe.ai/v1/systemone",
      Some("{}"),
      ApiKey.of("sk-logged"),
      configured = List("x-tag" -> "v1")
    )
    assert(!request.toString.contains("sk-logged"), s"logged request leaked the secret: $request")
    assert(request.toString.contains("<redacted"), s"no redaction marker in: $request")
  }

  // spec: client-configuration — Scenario: Rendering a failure — every
  // member of the family, enumerated
  test("rendering-a-failure") {
    val secret = "sk-fail00"
    familyMembers.foreach { member =>
      assert(
        !member.getMessage.contains(secret),
        s"${member.getClass.getSimpleName} rendered the secret: ${member.getMessage}"
      )
    }
  }

  // spec: client-configuration — Scenario: A secret embedded in a larger
  // structure — a structure holding the configuration renders redacted
  test("a-secret-embedded-in-a-larger-structure") {
    val cfg = configOf("sk-outer", "https://api.typesafe.ai", "jev-latest", Nil)
    assert(!List(cfg).toString.contains("sk-outer"))
    assert(!Map("cfg" -> cfg).toString.contains("sk-outer"))
    assert(!Outer(cfg).toString.contains("sk-outer"))
  }
}
