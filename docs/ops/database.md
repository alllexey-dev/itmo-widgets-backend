# Database

Backend runs on PostgreSQL 17. Flyway creates and evolves the schema, Hibernate
only validates (`ddl-auto=validate`), and `src/main/resources/db/migration/` is
the only source of DDL.

## Schema contract

- Applied migrations are immutable. A change is a new `V<n>__*.sql`; a mistake
  is corrected by a later migration. Never repair a checksum in place, never
  enable automatic baseline or clean, never use `ddl-auto=update`.
- Which migration created a table is the file in
  `src/main/resources/db/migration/` (each starts with a comment saying what it
  adds) and the feature document that owns the table; the rules for adding one
  are in [Migrations](#migrations).
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
- Take the next free number after the highest `V<n>` on `dev`. If another
  branch takes it first, rename yours before merging.
- A migration is never edited once it has reached `dev`: Flyway checksums
  include comments too.
- Expand only: no rename or drop of a column or table the previous release image
  still reads, so a rollback to that image stays image-only (Hibernate
  `validate` ignores extra columns). A drop waits for the release after the one
  that stopped reading it. The one exception so far is
  `V26__drop_alpha_clients.sql`, which drops the alpha's agent, pairing and web
  session tables in the same release as their replacement (no users had them):
  an image before V25 no longer starts against that schema, so its rollback is
  a database restore from the backup taken before the deploy.
- A release that changes the framework carries no migration, so it rolls back
  without a schema question.
- Every migration bumps the "Covers the schema of V1–V<n>" line of
  [`account-deletion.sql`](account-deletion.sql); one that references `users`
  also updates the script and `AccountDeletionRunbookTest`.
- Every migration has its own test class against real PostgreSQL in
  `src/test/.../platform/migration/` (`V<n><Name>Test`). No test pins the
  number of migrations: `MigrationScripts` reads them from
  `classpath:db/migration`, so a new script or a renumbering needs no test
  edit. A check that a later script may invalidate (a table that is dropped
  later) migrates to an explicit `target`.

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
credential into `service_credentials` and keeps `my_itmo_storage`, that V12
drops it, and that V10
adds the `GEMINI_API_KEY` row and the summary tables with their checks; the
store suite checks that a seed is written only into a row without a value.

How to run them is in [development](../development.md#build-and-tests).

One test JVM starts one container (`PostgreSqlTestDatabase`) and every suite
uses its own schemas in it. With Ryuk off the JVM's shutdown hook removes the
container on a normal exit; only a JVM killed without shutdown hooks leaves it
running. Testcontainers 2 comes from Spring Boot's BOM.

## Inspecting the migration history

Locally (see [development](../development.md)) or on the server, without
reading user tables:

```bash
docker compose --env-file deploy/.env -p itmowidgets-local -f deploy/compose.local.yaml exec -T database sh -ec \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank"'
```

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
rotation and replacement by an admin are in
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

## Connections and events

`connections` keeps only the SHA-256 of each connection token and, for an agent
or MCP server, the `sid` and end of its own ITMO.ID session.
`pair_challenges` keeps the SHA-256 of the claim secret, the 32-byte key
commitment and, between approval and its single claim, the HPKE ciphertext; the
claim deletes the ciphertext, and a job every minute expires codes, deletes
ciphertext left a minute past expiry and rows older than a day.
`client_events` is the per-user log agents long-poll; `client_users` keeps the
agent beta grant and `last_event_seq`, monotonic after rows go. An hourly job
(minute 25, Europe/Moscow) deletes expired events and all but each user's newest
1000, revokes approvals nobody claimed within 15 minutes and web connections
unused for 30 days, forgets the `sid` of revoked connections once the session
expired or the phone ended it, and deletes revoked connections 90 days later
once they hold no `sid`. Rules and settings are in
[connected clients](../contracts/connections.md).

## Admin data

`app_settings` holds runtime values an admin edits
(`app.latest`, `app.minimum`, `app.note`); a missing key falls back to the
environment. `admin_audit` is insert-only: role changes, moderation policy
changes, app version changes, manual reviews sync starts and credential
replacements with the acting admin, never payloads or tokens. Backend reads and
writes secrets only in `service_credentials`; `admin_audit` records
replacements without values.
