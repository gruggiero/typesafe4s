# Security policy

## Supported versions

typesafe4s is in 0.x: only the latest release receives security fixes.

| Version | Supported |
|---------|-----------|
| 0.1.x   | Yes       |
| < 0.1   | No        |

## Reporting a vulnerability

Please **do not open a public issue** for a security problem.

Report it privately through GitHub: on this repository, open the **Security** tab and choose
**Report a vulnerability** ([direct link](https://github.com/gruggiero/typesafe4s/security/advisories/new)).
Include the affected version and artifact (for example `typesafe4s-client-zio` 0.1.0), how to
reproduce the problem, and what an attacker could do with it.

The maintainers aim to acknowledge a report within a week. Once a fix is released, the advisory is
published with credit to the reporter, unless you prefer otherwise.

## Scope

In scope:

- Leaks of the API key: in a `toString`, an exception message, a log line, or a request sent
  somewhere other than the configured base URL. The SDK promises that no rendering of a
  configuration, request or failure contains the secret.
- Request smuggling or header injection through caller-supplied headers, question names or state.
- Vulnerabilities in a dependency that affect typesafe4s users. Dependabot alerts cover the
  build (see below); a report is still welcome if you find one first.

Out of scope: vulnerabilities in the TypeSafe service itself (report them to
[typesafe.ai](https://docs.typesafe.ai)), and problems that need an attacker who already controls
the application's configuration or classpath.

## Dependency scanning

The `Dependency graph` workflow submits the sbt build's dependencies to GitHub on every push to
`main`, so the repository's dependency graph and Dependabot alerts cover the libraries each
published artifact brings in.
