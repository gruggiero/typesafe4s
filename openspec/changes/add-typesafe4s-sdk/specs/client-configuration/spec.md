# Spec: Client Configuration

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Credential | Credential/resolve, Credential/render — where the secret comes from and how it is never shown | [credential.md](../../../../concepts/credential.md) |
| Evaluation | Evaluation/listJudges — listing the models an account may ask | [evaluation.md](../../../../concepts/evaluation.md) |

Retry is deliberately **not** cited: this spec holds a retry policy as one of its settings, but no requirement
here is Retry's behaviour — resolution, redaction, lifecycle and model listing are all this concept set's. The
policy's own behaviour is specified in the `retry-policy` spec, and citing it here would decorate this spec
rather than bind it.

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| TypesafeConfig | product type | Credential, service address, default model, allowance, policy, headers, log level |
| ApiKey | opaque type | A secret whose rendered form is always a redaction marker |

## ADDED Requirements

### Requirement: A setting is taken from the argument, then the environment, then the default

Credential/resolve SHALL take each setting from an explicit argument when one is given, otherwise from
the documented environment variable, otherwise from the documented default.

**Given** a setting may be supplied in more than one place
**When** it is resolved
**Then** an explicit argument is preferred to the environment
**And** the environment is preferred to the default

**Rationale**: One order, applied to every setting, means a caller learns it once. The order runs from
most specific to least, so the more deliberate expression always wins.

#### Scenario: Everything from the environment

**Given** only the credential is present in the environment
**When** a client is constructed with no arguments
**Then** construction succeeds using that credential
**And** the documented default service address and model are used

#### Scenario: An argument beats the environment

**Given** the environment names a service address and a model
**When** a client is constructed naming both explicitly
**Then** the explicit values are used

#### Scenario: The documented variables are the ones consulted

**Given** settings are being resolved
**When** the environment is read
**Then** the variables consulted are those the API documents for credential, service address, default
model and log level

### Requirement: A secret is never shown

Credential/render SHALL replace the secret with a redaction marker everywhere a configuration, a log
line or a failure is rendered.

**Given** a secret has been resolved
**When** anything holding it is rendered
**Then** a redaction marker appears
**And** the secret itself never does

**Rationale**: A leaked key is not a cosmetic defect — it is a credential in a log aggregator, and it
stays there. The rendering must be total: there must be no path that yields the secret.

#### Scenario: Rendering a configuration

**Given** a configuration holding a secret
**When** it is rendered
**Then** the secret is replaced by a redaction marker
**And** the settings that are not secret remain readable

#### Scenario: Logging an exchange at the most detailed level

**Given** logging is set to its most detailed level and headers are being logged
**When** an exchange is logged
**Then** the authorization value is replaced by a redaction marker

#### Scenario: Rendering a failure

**Given** any failure this SDK raises
**When** it is rendered
**Then** no secret appears anywhere in it

#### Scenario: A secret embedded in a larger structure

**Given** a configuration held inside another structure that is itself rendered
**When** that outer structure is rendered
**Then** the secret is still replaced by a redaction marker

### Requirement: A client releases what it holds

The system SHALL offer construction that releases the underlying resources at a determined moment, in
each ecosystem's own idiom, and SHALL offer an explicit release for construction outside such a scope.

**Given** a client has been constructed
**When** it is no longer needed
**Then** what it holds is released

**Rationale**: The exchange holds connections. A client that is dropped without release leaks them, and
the leak shows up much later as exhaustion under load.

#### Scenario: Construction within a scope

**Given** a client constructed within a scope
**When** the scope ends
**Then** what it holds is released

#### Scenario: A scope that ends in failure

**Given** a client constructed within a scope
**When** the work inside the scope fails
**Then** what it holds is still released

#### Scenario: Construction outside a scope

**Given** a client constructed outside any scope
**When** the caller has finished with it
**Then** an explicit release is available

### Requirement: The models an account may ask can be listed

Evaluation/listJudges SHALL report the models available to the account, and SHALL NOT judge a model name
locally.

**Given** a caller asks which models are available
**When** the request is answered
**Then** each model is reported with its description and release date

**Rationale**: The set of models changes without this SDK changing. A local check against a remembered
list would refuse models released after it was written.

#### Scenario: Listing the models

**Given** a caller asks for the list
**When** it comes back
**Then** each model carries its name, description and release date

#### Scenario: A model the listing does not name

**Given** a caller configures a model name absent from the listing
**When** a call is made
**Then** that name is sent as given
**And** no local judgement is made about whether it exists

## Properties (Ring 2)

### Property: Resolution follows one order for every setting

**Invariant**: For any combination of argument, environment value and default, the resolved value is the
argument if present, else the environment value if present, else the default — for every setting alike.

**Generator strategy**: `genResolution` — constructive. For each setting, independently draws
present-or-absent for the argument and for the environment, so all four combinations arise on a short
run; values are drawn from a small alphabet including the empty string, so "present but empty" is reached
by construction — the case where a naive check treats empty as absent. `classify` labels: the
present/absent combination, per setting.

```
forAll { (argument: Option[String], environment: Option[String], default: String) =>
  resolve(argument, environment, default) == argument.orElse(environment).getOrElse(default)
}
```

### Property: No rendering of anything ever contains the secret

**Invariant**: For any secret and any structure holding it, the rendered form of that structure does not
contain the secret's characters.

**Generator strategy**: `genSecretInContext` — constructive. Draws a secret from an alphabet that
deliberately includes short values and values that are substrings of ordinary settings, then embeds it in
a drawn context: a bare configuration, a configuration inside a larger structure, a logged header set, and
each member of the failure family — enumerated, so every rendering path is covered rather than sampled.
`classify` labels: context drawn, secret length bucket.

```
forAll { (secret: ApiKey, context: RenderContext) =>
  !render(context.holding(secret)).contains(secret.revealed)
}
```

## Requirement ↔ Test Cross-Reference

| Requirement | Test Name (Ring 2) | Test Name (Ring 3) | Ring 5 (parity) |
|-------------|--------------------|--------------------|-----------------|
| Requirement: A setting is taken from the argument, then the environment, then the default | `resolution-follows-one-order` | `—` | `— (resolution is pure)` |
| Requirement: A secret is never shown | `no-rendering-contains-the-secret` | `—` | asserted per row |
| Requirement: A client releases what it holds | `scope-releases-even-on-failure` | `—` | **asserted per row — idioms differ** |
| Requirement: The models an account may ask can be listed | `model-name-is-not-judged-locally` | `—` | asserted per row |

## Cross-Backend Parity (Ring 5)

**Applies: YES.** Construction and release are expressed in each ecosystem's own idiom, so the *shape* of
the thing a caller holds differs by row even though the behaviour must not.

| Behaviour | zio | ce | ox | kyo | pekko | Declared where |
|-----------|-----|----|----|-----|-------|----------------|
| Resolution order | yes | yes | yes | yes | yes | this spec |
| Secret never rendered | yes | yes | yes | yes | yes | this spec |
| Scoped construction releases at a determined moment | yes | yes | yes | yes | yes | this spec |
| Release still happens when the scope's work fails | yes | yes | yes | yes | yes | this spec |

No divergence is expected here — every ecosystem has a scoped-resource idiom. **That expectation is
itself the thing to test:** the parity suite constructs a client within each row's idiom, fails the work
inside it, and asserts release happened. A row where it does not is a defect, not a declared difference.

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| Obtaining the secret's characters from outside the SDK | Rendering is total; there must be no path that yields it | `compileErrors` on any read of the underlying value |
| Constructing a client with no resolvable credential, in source | The absence is knowable before the program runs when written in source | `compileErrors` |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| One resolution order applies to every setting | Requirement: A setting is taken from the argument, then the environment, then the default + Property: Resolution follows one order for every setting | tier 2 — one shared resolution function, so a setting cannot have its own order | `resolution-follows-one-order` |
| The documented variables are the ones consulted | Scenario: The documented variables are the ones consulted | tier 3 — scenario test naming each variable | client-configuration suite |
| The secret cannot be read out from outside | Requirement: A secret is never shown | tier 1 — opaque type with no accessor; unrepresentable | client-configuration compile-negative suite |
| No rendering of anything contains the secret | Requirement: A secret is never shown + Property: No rendering of anything ever contains the secret | tier 3 — property test over enumerated rendering contexts; **tier-justified**: tier 1 prevents *reading* the secret, but cannot prevent a future rendering from being written badly — no lint rule is installed that would (`openspec/capability-profile.md`) | `no-rendering-contains-the-secret` |
| Authorization is redacted in detailed logs | Scenario: Logging an exchange at the most detailed level | tier 3 — property test with the logged-header context | `no-rendering-contains-the-secret` |
| No failure renders the secret | Scenario: Rendering a failure | tier 3 — property test enumerating every failure member | `no-rendering-contains-the-secret` |
| A missing credential fails at construction, naming the variable | Requirement: A setting is taken from the argument, then the environment, then the default | tier 2 — construction refuses; covered also by the error-model spec | client-configuration suite |
| Scoped construction releases, including when the work fails | Requirement: A client releases what it holds | tier 3 — parity suite per row; **tier-justified**: the idiom differs per ecosystem, so no single type expresses it | cross-backend parity suite |
| An explicit release exists outside a scope | Scenario: Construction outside a scope | tier 1 — the operation is part of the client surface | client-configuration type contract |
| A model name is not judged locally | Requirement: The models an account may ask can be listed | tier 2 — no local list exists to judge against, by construction | `model-name-is-not-judged-locally` |
| Listed models carry name, description and release date | Scenario: Listing the models | tier 3 — recorded fixture; **CONFIRMED 2026-09-19** (task 12.3): the live shape is `{"models":[{"name","description","release_date"}]}` — corrected from the prose-assumed `{"data":[{"id","display_name","created_at"}]}` | client-configuration suite |

## Implementation Anchors

The one place in this spec where code identifiers appear.

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `TypesafeConfig` | case class | `typesafe4s-client/shared` | Fields: api key, base url, model, per-attempt timeout, retry policy, headers, log level |
| `ApiKey` | opaque type | `typesafe4s-core` | No accessor; `toString` yields a redaction marker |
| Environment variables | — | — | `TYPESAFE_API_KEY`, `TYPESAFE_BASE_URL`, `TYPESAFE_DEFAULT_MODEL`, `TYPESAFE_LOG_LEVEL` |
| Documented defaults | — | — | `https://api.typesafe.ai`, `jev-latest`, 10s per attempt |
| Scoped construction | per-row facade | `typesafe4s-client/{zio,ce,ox,kyo,pekko}` | ZIO `Scope`, Cats Effect `Resource`, Ox scope, Kyo, Pekko |
| Models listing | `GET /v1/models` | `typesafe4s-client/shared` | Body shape confirmed 2026-09-19 (task 12.3): `{"models":[{name,description,release_date}]}` |
| Property suites | munit `ScalaCheckSuite` | `typesafe4s-client/shared/src/test/scala/` | Compiles into every row |
| Parity assertions | shared suite over the carrier | `integration-tests/shared/src/test/scala/` | `sbt parityAll` |
