# ITMO.Widgets Backend

Kotlin, Spring Boot 4, PostgreSQL 17 with Flyway, Java 21. How to work on it is
in [CONTRIBUTING.md](CONTRIBUTING.md), local setup in
[docs/development.md](docs/development.md), everything else in
[docs/README.md](docs/README.md).

## Build and test

```bash
./gradlew build                          # compile, every test suite, ktlint
./gradlew test --tests '<pattern>'       # one class or pattern
```

The PostgreSQL tests need Docker (Testcontainers); with colima set
`DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and
`TESTCONTAINERS_RYUK_DISABLED=true`. Do not skip them.

## Rules

- Backend is the authority for authorization and privacy: Spring Security
  denies by default, and every new route has a test for the allowed and the
  denied path. It never trusts the client to enforce access.
- Responses about other users carry viewer-scoped capabilities, never their
  raw privacy settings.
- Requests are authenticated with the ITMO.ID access token or a connection
  token (`iwc_` bearer or the web's `iw_client` cookie,
  [connected clients](docs/contracts/connections.md)); the user's refresh token
  is never sent to or stored by Backend, nor any ITMO token of a connected
  client: only its session id.
- Applied Flyway migrations are immutable, comments included; a change is a new
  `V<n>__*.sql`. Rules: [docs/ops/database.md](docs/ops/database.md#migrations).
- Never mutate production data for testing; logs carry no tokens or user data.
- Never commit `.env`, Firebase keys, dumps or user data.
