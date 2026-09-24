# Database

Backend runs on PostgreSQL 17. Flyway creates and evolves the schema, Hibernate
only validates (`ddl-auto=validate`), and `src/main/resources/db/migration/` is
the only source of DDL. Both environments run this stack; the production cutover
from MariaDB is recorded in [deployment](deployment.md).

## Schema contract

- Applied migrations are immutable. A change is a new `V<n>__*.sql`; a mistake
  is corrected by a later migration. Never repair a checksum in place, never
  enable automatic baseline or clean, never use `ddl-auto=update`.
- `V1__initial_postgresql_schema.sql` creates the pre-friendship schema;
  `V2__friendships.sql` adds explicit friendships and converts legacy rows;
  `V3__friends_visibility.sql` adds the friends audience;
  `V4__subject_links.sql` adds subject links and moderation
  ([contract](../contracts/subject-links.md));
  `V5__web_sessions_and_admin.sql` adds the `ADMIN` role, phone-approved web
  login challenges, web sessions, `app_settings` and the insert-only
  `admin_audit` ([web](../contracts/web.md), [admin](../contracts/admin.md)).
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

## Files

| File | Purpose |
|---|---|
| `src/main/resources/db/migration/V4__subject_links.sql` | subject links, revisions, audiences, votes, saves, pins, user subject flows and the shared moderation tables |
| `src/main/resources/db/migration/V5__web_sessions_and_admin.sql` | `ADMIN` in the role check, `web_login_challenges`, `web_sessions`, `app_settings`, `admin_audit` |
| `deploy/compose.yaml` | server stack: `backend` and `database`, only `backend` joins the external `web` network |
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
fake MyITMO and Firebase clients and verifies start, restart, preserved tokens
and settings, upstream failure and retry, and fatal schema drift.

```bash
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true \
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
```

With Docker Desktop the two variables are unnecessary. `build.gradle.kts` pins
Testcontainers 1.21.4 for Docker Engine 29 support.

## Local environment

```bash
umask 077
cp deploy/.env.example deploy/.env      # then set both passwords locally
docker compose --env-file deploy/.env -p itmowidgets-local -f deploy/compose.local.yaml up -d --wait
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew bootRun
```

`bootRun` needs `DB_URL=jdbc:postgresql://localhost:55432/itmowidgets`, the
matching `DB_USER`/`DB_PASSWORD`, `FIREBASE_KEY_PATH` and, before the first start
against an empty database, a valid technical-account `MY_ITMO_REFRESH_TOKEN`.
Pass secrets through the run configuration, never literally in a shell command.

Check the migration history without reading user tables:

```bash
docker compose --env-file deploy/.env -p itmowidgets-local -f deploy/compose.local.yaml exec -T database sh -ec \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank"'
```

Stop with `down`; `down -v` is not part of the workflow.

## Technical credential

`MyItmoTokenStore` persists the technical MyITMO credential in short independent
transactions; a rotated bundle is committed before the OAuth callback returns.
`MY_ITMO_REFRESH_TOKEN` is a seed only: startup writes it when no refresh token
is stored and never replaces a stored rotated one, so a stale override in `.env`
is harmless but should be removed after the first successful start. Missing or
invalid credentials degrade sport refresh (catalog retries every ten minutes,
reference data hourly) without stopping HTTP; database, Flyway, validation and
token-store failures still stop startup.

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
the cookie token; ended sessions are deleted 30 days after expiry, and the admin
dashboard counts recent ones. `app_settings` holds runtime values an admin edits
(`app.latest`, `app.minimum`, `app.note`); a missing key falls back to the
environment. `admin_audit` is insert-only: role changes, moderation policy
changes and app version changes with the acting admin, never payloads or
tokens. None of these tables stores a MyITMO credential.
