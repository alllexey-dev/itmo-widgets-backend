# Database

Backend runs on PostgreSQL 17. Flyway creates and evolves the schema, Hibernate
only validates (`ddl-auto=validate`), and `src/main/resources/db/migration/` is
the only source of DDL. Both environments run this stack; the production cutover
from MariaDB is recorded in [deployment](deployment.md).

## Schema contract

- Applied migrations are immutable. A change is a new `V<n>__*.sql`; a mistake
  is corrected by a later migration. Never repair a checksum in place, never
  enable automatic baseline or clean, never use `ddl-auto=update`.
- Which migration created a table is the file in
  `src/main/resources/db/migration/` (each starts with a comment saying what it
  adds) and the feature document that owns the table; the rules for adding one
  are in [Migrations](#migrations).
- V4 was rewritten and V5 removed before any production use: the replaced first
  resource iteration had applied its own V4 and V5 on development only. By the
  user's decision of 2026-09-23 the development resource tables are recreated
  instead of migrated: before deploying this V4 there, back up, drop the tables
  of the old V4/V5 and delete their `flyway_schema_history` rows (versions 4 and
  5), then let startup apply the new V4. Production never ran V4 or V5.
- The role initializer (`deploy/postgres/001-app-role.sh`) creates only the
  non-superuser application role, which owns `public` so startup can migrate.
  It runs only on an empty data directory; later credential changes are a
  coordinated rotation, not an `.env` edit.
- Migrations must pass against real PostgreSQL; H2 is not a compatibility check.
- Keep the container mount target `/var/lib/postgresql/data`; a major PostgreSQL
  upgrade is a separately verified procedure.

## Migrations

Rules for every new `V<n>__*.sql`:

- Flyway runs with `validate-on-migrate=true` and without `outOfOrder`, so a
  lower version that reaches a database after a higher one fails validation.
  Migrations merge and reach `dev` and production in ascending order.
- V1–V10 are applied in production and never edited; neither is any later
  migration once it has reached `dev`.
- Expand only: no rename or drop of a column or table the previous release image
  still reads, so a rollback to that image stays image-only (Hibernate
  `validate` ignores extra columns). A drop waits for the release after the one
  that stopped reading it, and for the end of its rollback window.
- A release that changes the framework (the Spring Boot 4 batch) carries no
  migration, so it rolls back without a schema question.
- Every migration bumps the "Covers the schema of V1–V<n>" line of
  [`account-deletion.sql`](account-deletion.sql); one that references `users`
  also updates the script and `AccountDeletionRunbookTest`.
- Every migration has its own test class against real PostgreSQL in
  `src/test/.../platform/migration/` (`V<n><Name>Test`). No test pins the
  number of migrations: `MigrationScripts` reads them from
  `classpath:db/migration`, so a new script or a renumbering needs no test
  edit. A check that a later script may invalidate (a table that is dropped
  later) migrates to an explicit `target`.
- Numbers are not chosen by the author: the integrator assigns them at merge from
  the ledger below and renames the file if needed.

Shipped in Backend 1.8.0 (the rollback to 1.7.0 stays image-only, see
[deployment](deployment.md#180-to-170)):

| V | File | Content | Status |
|---|---|---|---|
| V11 | `V11__device_app_version.sql` (BK-VER1) | `devices.app_version`, `app_build`, `app_platform`, `app_distribution`, `app_version_seen_at`, all nullable, no defaults, no backfill (no table rewrite); does not touch the 1.2.1 rollback window | merged 2026-10-10, released in 1.8.0 |

Planned for Backend 1.9.0:

| V | File | Content | Merges after |
|---|---|---|---|
| V12 | `V12__drop_my_itmo_storage.sql` | drops `my_itmo_storage` (no foreign key; kept by V8 for the image-only rollback to 1.2.1) | the rollback window from 1.7.0 to 1.2.1 closes (about 2026-10-16) |
| V13 | `V13__device_platform.sql` | `devices.platform`, `alerts_allowed`, `push_provider`, `created_at`, all with defaults; `fcm_token` stays; `app_version` already exists from V11 and is not added again | V12 |
| V14 | reserved | only if account deletion needs a table (a confirmation code or a deletion tombstone); otherwise the number is released | V13 |

V15 and later are free; ask the integrator for a number.

## Files

| File | Purpose |
|---|---|
| `src/main/resources/db/migration/V<n>__*.sql` | the schema, one immutable file per version; see [Migrations](#migrations) |
| `deploy/compose.yaml` | server stack: `backend`, `database` and `gemini-proxy`; only `backend` joins the external `web` network and the internal `gemini` network of the proxy |
| `deploy/gemini-proxy/config.example.json` | the shape of the `gemini-proxy` config with placeholders; the real `config.json` is ignored by git |
| `deploy/compose.local.yaml` | isolated local PostgreSQL on loopback port 55432 |
| `deploy/Dockerfile` | Java 21 runtime, UID/GID 10001, copies exactly `itmo-widgets-backend.jar` |
| `deploy/postgres/001-app-role.sh` | application role initializer |
| `deploy/.env.example` | non-secret template; copy to an ignored `.env` |

Never print `.env`, resolved Compose configuration or container environment;
validate with `docker compose config --quiet`.

## Tests

Repository, migration, startup and concurrency suites run a disposable
PostgreSQL 17 through Testcontainers with synthetic data; they need Docker, not
`.env`, credentials or a network. The startup suite runs the real listeners with
fake MyITMO, ISU, Gemini and Firebase clients and verifies start, restart,
preserved tokens and settings, upstream failure and retry, and fatal schema
drift. The migration suites, one class per script in `platform/migration`,
check each script on its own schema: for example that V8 copies the My ITMO
credential into `service_credentials` and keeps `my_itmo_storage`, and that V10
adds the `GEMINI_API_KEY` row and the summary tables with their checks; the
store suite checks that a seed is written only into a row without a value.

```bash
scripts/verify.sh          # ./gradlew build with JDK 21, colima's DOCKER_HOST and Ryuk off
scripts/verify.sh leaks    # test containers left by a killed test JVM
```

One test JVM starts one container (`PostgreSqlTestDatabase`) and every suite
uses its own schemas in it. With Ryuk off the JVM's shutdown hook removes the
container on a normal exit; only a JVM killed without shutdown hooks leaves it
running. Each container carries the labels `itmo-agents.run` (the
`ITMO_AGENTS_RUN` of the `verify.sh` call), `itmo-agents.pid` (the test JVM)
and `itmo-agents.dir` (the checkout); `verify.sh leaks` lists the labelled
containers whose JVM is gone and exits 1 if there are any. With Docker Desktop
`DOCKER_HOST` is unnecessary. Testcontainers 2 (2.0.5, which supports Docker
Engine 29) comes from Spring Boot's BOM.

## Local environment

```bash
umask 077
cp deploy/.env.example deploy/.env      # then set both passwords locally
docker compose --env-file deploy/.env -p itmowidgets-local -f deploy/compose.local.yaml up -d --wait
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew bootRun
```

`bootRun` needs `DB_URL=jdbc:postgresql://localhost:55432/itmowidgets`, the
matching `DB_USER`/`DB_PASSWORD`, `FIREBASE_KEY_PATH` and, while the
`MY_ITMO_REFRESH_TOKEN` row of `service_credentials` has no value (an empty
database), a valid technical-account `MY_ITMO_REFRESH_TOKEN` seed; the ISU check
likewise needs an `ISU_KEYCLOAK_IDENTITY` seed while its row is empty. Pass
secrets through the run configuration, never literally in a shell command.

Check the migration history without reading user tables:

```bash
docker compose --env-file deploy/.env -p itmowidgets-local -f deploy/compose.local.yaml exec -T database sh -ec \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank"'
```

Stop with `down`; `down -v` is not part of the workflow.

## Service credentials

`ServiceCredentialStore` keeps the technical My ITMO tokens and the ISU cookie
in `service_credentials`, each call in its own short transaction; a rotated
bundle is committed before the OAuth callback returns. `MY_ITMO_REFRESH_TOKEN`
and `ISU_KEYCLOAK_IDENTITY` are seeds only: startup writes one only into a row
without a value and never replaces a stored one, so a stale seed in `.env` is
harmless but should be removed after the first successful use. Missing or
invalid credentials degrade sport refresh (catalog retries every ten minutes,
reference data hourly) and the ISU check without stopping HTTP; database,
Flyway, validation and credential-store failures still stop startup. Statuses,
rotation, replacement by an admin and the rollback rules are in
[service credentials](service-credentials.md).

## Refresh outcomes and retention

`SportCatalogService` writes catalog rows and their `sport_update_logs` row in
one short transaction; HTTP, queue transitions and FCM stay outside it. A row
holds a timestamp, `SUCCESS`/`PARTIAL`/`FAILED`, elapsed milliseconds, received,
new, updated and skipped counts and an optional category (`AUTH`, `NETWORK`,
`HTTP`, `MAPPING`, `PERSISTENCE`, `INTERNAL`); never an exception message, body
or token. Missing lessons are never deleted because a response omits them.

Daily cleanup at 04:15 Europe/Moscow removes logs older than the retention
cutoff in batches:

| Property | Environment override | Default |
|---|---|---|
| `itmowidgets.retention.sport-update-log-days` | `SPORT_UPDATE_LOG_RETENTION_DAYS` | 90 |
| `itmowidgets.retention.batch-size` | `TECHNICAL_LOG_RETENTION_BATCH_SIZE` | 1000 |

The tracked Compose file does not forward these optional variables; add them
through a reviewed override. Cleanup never touches lessons, queues, bookings,
users, settings or quota history.

## Web sessions and admin data

`web_login_challenges` keeps only the SHA-256 of the poll secret and is emptied
of rows older than a day every minute. `web_sessions` keeps only the SHA-256 of
the cookie token; ended sessions are deleted 90 days after expiry, and the admin
dashboard counts recent ones. Session limits are in [web login](../contracts/web.md). `app_settings` holds runtime values an admin edits
(`app.latest`, `app.minimum`, `app.note`); a missing key falls back to the
environment. `admin_audit` is insert-only: role changes, moderation policy
changes, app version changes, manual reviews sync starts and credential
replacements with the acting admin, never payloads or tokens. Backend reads and
writes secrets only in `service_credentials`; `my_itmo_storage` keeps the MyITMO
tokens as of V8 for an image-only rollback until the next release drops it;
`admin_audit` records replacements without values.
