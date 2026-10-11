# Development

## Prerequisites

- JDK 21. On macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
- A Docker engine, for the local database and for the tests (Testcontainers).
  Docker Desktop works as is. With colima, point the tools at its socket and
  turn off Ryuk (the test JVM then removes its container on exit):

  ```bash
  export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
  export TESTCONTAINERS_RYUK_DISABLED=true
  ```

Every dependency is a release from Maven Central; there is nothing to build or
publish locally first.

## Build and tests

```bash
./gradlew build
```

`build` compiles, runs every test suite and the checks:

- `test`: unit, controller, contract and PostgreSQL tests. The PostgreSQL ones
  start one disposable `postgres:17-alpine` container per test JVM; they need
  Docker, not `deploy/.env`, credentials or the network. Without Docker they
  fail; they are never skipped.
- `compatCore120Test`, `compatCore170Test`: released Core versions decode the
  golden fixtures in `src/test/resources/contract/`, as installed apps do.
- `architectureTest`: the package rules of [architecture](architecture.md).

One test class or pattern: `./gradlew test --tests '*FcmServiceTest'`.

Two generated files fail the build when they are stale. Regenerate them only
for an intended wire change ([wire compatibility](contracts/compatibility.md)):

```bash
./gradlew test --rerun --tests '*OpenApiSnapshotTest' -Popenapi.record=true            # docs/openapi.json
./gradlew test --tests 'dev.alllexey.itmowidgets.backend.contract.*' -Pcontract.record=true  # golden fixtures
```

## Running locally

1. Start a local PostgreSQL 17 on `127.0.0.1:55432`:

   ```bash
   umask 077
   cp deploy/.env.example deploy/.env   # set POSTGRES_ADMIN_PASSWORD and DB_PASSWORD; .env is ignored by git
   docker compose --env-file deploy/.env -p itmowidgets-local -f deploy/compose.local.yaml up -d --wait
   ```

   The first start creates the database and the application role
   (`deploy/postgres/001-app-role.sh`). Stop with `down`; `down -v` deletes
   the data.

2. Run Backend against it; Flyway creates the schema on the first start:

   ```bash
   DB_URL=jdbc:postgresql://localhost:55432/itmowidgets DB_USER=itmowidgets \
   DB_PASSWORD="$(sed -n 's/^DB_PASSWORD=//p' deploy/.env)" FIREBASE_KEY_PATH= ./gradlew bootRun
   ```

   `DB_URL`, `DB_USER` and `DB_PASSWORD` are required. `FIREBASE_KEY_PATH`
   must be set, but may be empty. Every other setting has a default in
   `src/main/resources/application.properties`; do not source the whole
   `deploy/.env`, it holds the development server's settings.

The server listens on port 8080; `GET /api/app/version-info` answers without
authentication. Every other route needs an ITMO.ID access token (checked
against the `id.itmo.ru` keys) or a web session approved from the app.

## What is off without credentials

| Missing | Effect |
|---|---|
| Firebase key (`FIREBASE_KEY_PATH` empty) | no Firebase; push messages are logged as not sent instead of delivered |
| My ITMO technical token (`MY_ITMO_REFRESH_TOKEN` seed, empty `service_credentials` row) | the sport catalog refresh fails and retries; sport queues and teacher names in the web admin have no fresh data |
| ISU cookie (`ISU_KEYCLOAK_IDENTITY`) | teacher reviews stay unchecked against ISU |
| Gemini (`AI_SUMMARY_ENABLED=false`, the default) | no AI summaries |
| `REVIEWS_SYNC_ENABLED=false` (the default) | no copy of reviews from the Reviews project |

HTTP keeps working in every case. The seeds and how they are stored are in
[service credentials](ops/service-credentials.md). Use a technical account,
never your own token.
