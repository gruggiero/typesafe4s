# Concept: Credential

## Concept specification

```
concept Credential
purpose
    authenticate an exchange without ever revealing the secret, so that a key cannot escape
    through a log line, a rendered config or a stack trace
state
    secret: Credential -> Opaque          # never rendered
    source: Credential -> Origin          # argument, environment, or absent
actions
    resolve [ argument: Opaque ; environment: Opaque ] => [ credential: Credential ]
    resolve [ neither supplied ] => [ error: "no API key; set TYPESAFE_API_KEY" ]
    present [ credential ] => [ authorization: Header ]
    render [ credential ] => [ redacted: Text ]
operational principle
    an explicit argument wins over the environment, which wins over nothing at all; and however
    the credential is later printed — in a config object, a debug log, or an error — what appears
    is a redaction marker, never the secret
```

**Rendering a secret is a defect, not a cosmetic issue.** `render` is total: there is no path that yields the
secret. That includes a config object's own string form, headers at the most verbose log level, and every error
message.

**Absence fails locally.** A missing key is detected at construction, before any network call, and the error names
the environment variable to set.

## Implementation map

| Element | Code |
|---|---|
| state *secret* | `ApiKey` — `final class ApiKey private (private val value: String) extends AnyVal` (core; http-transport operand, client-configuration contract). The raw value is reachable only inside the companion — no accessor is published |
| state *source* | `TypesafeConfig.resolve` — the argument-then-environment order is fixed in its body (client-configuration spec) |
| action *resolve* | `TypesafeConfig.resolve` (typesafe4s-client) — argument wins over `environment(apiKeyEnv)`; absent/blank resolves to `MissingCredential("TYPESAFE_API_KEY")` (client-configuration spec) |
| action *present* | `ApiKey.authorization` — the `(String, String)` header pair `Authorization: Bearer <key>`; `HttpRequest.exchange` attaches it (http-transport spec) |
| action *render* | `ApiKey.toString` → `"<redacted api key>"`; `ApiKey.redact` scrubs the raw value out of rendered text (config renders, logged headers, echoed failures — client-configuration spec) |

## Value domains

| Domain | Values / source | Confirmed |
|---|---|---|
| Environment variable | `TYPESAFE_API_KEY` | 2026-09-17, from docs |
| Scheme | `Authorization: Bearer <API_KEY>` | 2026-09-17, from docs |
| Sibling variables | `TYPESAFE_BASE_URL`, `TYPESAFE_DEFAULT_MODEL`, `TYPESAFE_LOG_LEVEL` — not secret | 2026-09-17, from docs |
| Key format | **not published** | **MUST-CONFIRM** — do not validate a key's shape locally. Let the server reject it; a local format check would reject valid future keys. |

## Synchronizations

```
sync AuthenticateExchange
when {
    Evaluation/ask: [ subject ; asked ]
}
where {
    Credential/present: [ credential ] => [ authorization ]
}
then {
    Evaluation/ask: [ subject ; asked ; authorization ]
}
```

impl: `HttpRequest.exchange` attaches the produced `Authorization` header to every exchange
request (http-transport spec); the `Evaluation/ask` party is `Client.askTyped`/`Client.askDynamic`
over `internal.ClientCio` (effect-portability spec).

## Deviations from the pattern

- Redaction is currently an **unenforced claim**: no Scalafix or WartRemover rule is installed
  (`openspec/capability-profile.md`), so nothing mechanically prevents a future `toString` from leaking the secret.
  The specs must discharge it with tests, and a lint rule would be stronger. The discharge that
  exists: `ApiKey` keeps the raw value reachable only inside its companion (`private[typesafe4s]`
  `authorization`/`redact`), and `toString` returns the redaction marker — verified by the
  client-configuration oracle.
