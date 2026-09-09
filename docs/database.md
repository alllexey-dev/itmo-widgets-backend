# PostgreSQL and Flyway

## Scope and release boundary

Backend now targets **PostgreSQL 17**. Flyway creates and evolves the schema;
Hibernate uses `ddl-auto=validate`, not `update` or `create`. This is a fresh-start
cutover after Android v2.1, **not an in-place MariaDB migration**. No import of old
users, devices, friendships, settings, sessions, or sport queues is provided.
Application and database versions remain independent from the Android version.
This source uses Backend and Core `1.2.0-SNAPSHOT`. The coordinated unreleased
privacy contract now returns viewer capabilities; see [privacy](privacy.md). Core
is published only to MavenLocal for local client verification, not publicly.

Adding these files does not deploy, stop, clear, or replace either server
installation. Development rehearsal and production cutover are separate,
explicitly approved operations. Production cutover additionally requires v2.1 to
be released. Do not point this build at an existing MariaDB database.

A reset means creating a new PostgreSQL cluster in a **new directory**, not
running `DROP`, deleting volumes, or reusing MariaDB files. Keep the old database,
artifact, configuration, and verified backup until the rollback window is closed.
Permanently deleting those backups or old data requires a separate decision.

## Schema contract

- `src/main/resources/db/migration/` is the only source of application DDL.
- `V1__initial_postgresql_schema.sql` creates an empty current schema. Subsequent
  changes get a new `V2__description.sql`, `V3__description.sql`, and so on.
- Never edit an applied migration, enable automatic baseline/clean, or use
  `ddl-auto=update` to work around validation failures. Investigate the mismatch.
- The database role initializer creates only the application role and its schema
  permissions. Flyway creates tables, indexes, constraints, and history.
- The application role is not a PostgreSQL superuser and cannot create databases
  or roles. It owns `public` so that application startup can run Flyway migrations.
- Migrations must pass against a real PostgreSQL instance. H2 alone is not a
  PostgreSQL schema compatibility check.
- Before any later schema-affecting deployment, back up PostgreSQL and check the
  old binary's compatibility with the new schema. A new migration is not
  automatically reversible.

Spring Boot runs Flyway migrations during startup; the application uses it as
the only schema-generation mechanism rather than mixing it with Hibernate DDL
or `schema.sql`. [Spring Boot database initialization](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html).

## Files and configuration

| File | Purpose |
|---|---|
| `deploy/compose.local.yaml` | Isolated local PostgreSQL, loopback port 55432, named volume |
| `deploy/compose.yaml` | Server application and database; only application joins `web` |
| `deploy/postgres/001-app-role.sh` | Fresh-cluster non-superuser role initialization |
| `deploy/Dockerfile` | Java 21 runtime, non-root UID/GID 10001, one exact JAR |
| `deploy/.env.example` | Non-secret template, development target defaults |
| `deploy/.dockerignore` | Build context includes only Dockerfile and staged JAR |

Copy the example into an ignored `.env`; choose different high-entropy admin and
application passwords per environment. Do not print `.env`, resolved Compose
configuration, container environment, SQL containing passwords, or credentials in
logs. Use `docker compose config --quiet` for validation. `DB_URL` is assembled
inside the server Compose file; a stale MariaDB URL in the old `.env` is not used.

The initializer runs **only on an empty PostgreSQL data directory**. Changing
`DB_USER`, `DB_PASSWORD`, or `POSTGRES_ADMIN_PASSWORD` later does not rotate an
existing database credential. Rotate the database role and application environment
as a coordinated operation. If initialization fails, stop and investigate rather
than repeatedly restarting a partly initialized cluster.

The official PostgreSQL 17 image persists data at `/var/lib/postgresql/data`;
keep that exact container mount target. Major upgrades require a separately
verified upgrade procedure, not just changing `POSTGRES_IMAGE`.
[Official image documentation](https://hub.docker.com/_/postgres).
The role initializer uses `psql` environment interpolation to avoid passwords in
command arguments. [PostgreSQL 17 psql documentation](https://www.postgresql.org/docs/17/app-psql.html).

## Testing

Repository and migration tests run a disposable real PostgreSQL 17 through
Testcontainers. They require a running Docker-compatible engine and permission
to pull the test image, not `deploy/.env`, a manually started local database,
Firebase credentials, or a MyITMO service-account token. The persistence test
slice does not start the application's external-service startup listeners.
Do not redirect test datasource settings to development or production.

With Docker Desktop or a normally discoverable Docker Engine, run from the
Backend repository:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test bootJar
```

For the default Colima profile on macOS:

```bash
colima start
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
unset TESTCONTAINERS_RYUK_DISABLED
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test bootJar
```

Keep Ryuk enabled so disposable resources are cleaned up. Adapt the socket path
if deliberately using another Colima profile; configuring the Docker CLI context
alone does not necessarily configure the Java Docker client.
[Testcontainers runtime setup](https://java.testcontainers.org/supported_docker_environment/).

`build.gradle.kts` pins Testcontainers **1.21.4** to retain the Spring Boot 3.x
compatible API with Docker Engine 29 support; do not remove the pin without
retesting that engine. [Testcontainers 1.21.4 release](https://github.com/testcontainers/testcontainers-java/releases/tag/1.21.4).

## Fresh local environment

Run from the Backend repository. Use only local or development credentials.

```bash
umask 077
cp deploy/.env.example deploy/.env
# Edit deploy/.env locally: replace both password placeholders.
docker compose --env-file deploy/.env -p itmowidgets-local \
  -f deploy/compose.local.yaml config --quiet
docker compose --env-file deploy/.env -p itmowidgets-local \
  -f deploy/compose.local.yaml up -d --wait
```

For `bootRun`, supply `DB_URL=jdbc:postgresql://localhost:55432/itmowidgets`,
`DB_USER` and `DB_PASSWORD` matching that local database, and `FIREBASE_KEY_PATH`
pointing to a readable development service-account file through the IDE/run
configuration or a trusted local environment file. Also supply a **valid,
environment-specific technical-account `MY_ITMO_REFRESH_TOKEN` before the first
application start**. Do not pass secrets literally in shell commands. Adjust the
database name/port if the local `.env` changes them.

A fresh database has no service-account token. `SportUpdateService` fetches the
catalog during context startup, and a missing/invalid token or failed upstream
request can abort startup before the HTTP smoke endpoint is available. Do not
wait until after `bootRun` or the smoke test to provision this credential.
Firebase credentials must also be ready before startup because its bean is
initialized immediately. Once those prerequisites and the tests above pass, run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew bootRun
```

Once startup succeeds, use a separate terminal:

```bash
curl --fail --silent --show-error http://localhost:8080/api/app/version-info
```

The first application start must apply V1, then Hibernate must validate. Check
history without reading user tables:

```bash
docker compose --env-file deploy/.env -p itmowidgets-local \
  -f deploy/compose.local.yaml exec -T database sh -ec \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
    "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank"'
```

Technical credentials are persisted by `MyItmoTokenStore` in short independent
transactions. Every Storage setter is durable, and a rotated bundle is committed
atomically before the OAuth callback returns; a later catalog/queue rollback does
not undo it. No cached JPA entity or database lock is held during the OAuth request.
Persistence failure is reported to the caller, not acknowledged as a successful
rotation. The external OAuth exchange and PostgreSQL commit are not a distributed
transaction; do not blindly retry an already rotated credential on save failure.

`MY_ITMO_REFRESH_TOKEN` is a **seed only**: startup writes it only when the stored
refresh token is absent. Restarting with a stale override never replaces a stored
rotated bundle. After the first successful startup and confirmed persistence,
remove the unnecessary override from the run configuration and restart (or
recreate the application container). Confirm the restart applies no new migration,
Hibernate validation succeeds and catalog authentication still works. A deliberate
technical-account replacement needs a separate operational action; changing the
seed does not replace an existing credential. Never use a user's token or
production credentials for local runs, or print stored tokens to verify them.

To stop local PostgreSQL while retaining data:

```bash
docker compose --env-file deploy/.env -p itmowidgets-local \
  -f deploy/compose.local.yaml down
```

No `down -v` or automatic database reset command is part of the normal workflow.

## Future server cutover

Execute only after approval, development first. The table describes the **target
PostgreSQL deployment**, not a claim that the server has already changed.

| Setting | Development | Production |
|---|---|---|
| Deployment directory | `/mnt/raid/srv/web/itmowidgets-dev` | `/mnt/raid/srv/web/itmowidgets` |
| `COMPOSE_PROJECT_NAME` | `itmowidgets-dev` | `itmowidgets` |
| `APP_CONTAINER_NAME` | `itmowidgets-dev` | `itmowidgets` |
| `DB_CONTAINER_NAME` | `itmowidgets-dev-db` | `itmowidgets-db` |
| `BACKEND_IMAGE` | `itmowidgets-dev-backend:postgres` | `itmowidgets-backend:postgres` |
| `DB_HOST_PORT` | `3301` | `3302` |
| `POSTGRES_DATA_DIR` | `/mnt/raid/srv/dbs/itmowidgets-dev-postgres` | `/mnt/raid/srv/dbs/itmowidgets-postgres` |
| `FIREBASE_KEY_FILE` | `/mnt/raid/srv/web/itmowidgets-dev/firebase-postgres.json` | `/mnt/raid/srv/web/itmowidgets/firebase-postgres.json` |
| Public URL | `https://dev.widgets.alllexey.dev` | `https://widgets.alllexey.dev` |

Ports remain loopback-only at the established host numbers, but now speak the
**PostgreSQL** protocol, not MySQL. Inside the Compose-private network the hostname
is `database` and port is 5432. Existing app container names and port 8080 are
preserved for `nginx-hub`; do not change normal domain routing. New Compose service
names are `backend` and `database`, unlike some old deployment service names.

### 1. Prepare and rehearse

1. Verify the source revision, tests, PostgreSQL migration integration tests, and
   `bootJar` with Java 21. Record Backend/Core versions and exact image digests.
2. Rehearse a fresh start and restart on an isolated local database. Then complete
   an approved development cutover and smoke tests before production scheduling.
3. Confirm Android v2.1 is available and handles an empty Backend: re-registration,
   FCM registration, default friend-only privacy, and rebuilding sport queues.
   Old queue subscriptions and relationships will not survive. Update version
   metadata intentionally; `minVersion` is metadata, not server-side blocking.
4. Schedule a maintenance window. Inventory the real old Compose service names,
   project name, image IDs, MariaDB bind mounts, environment file, and Firebase
   path. Read no secret values into chat or copied reports. Keep both environments
   separate; never copy development `.env` or credentials into production.
5. Stage the new deployment files outside the active directory, including
   `postgres/001-app-role.sh`. Stage the tested JAR as exactly
   `itmo-widgets-backend.jar`. Prepare a new private `.env` based on the example,
   with the correct target row from the table. Provision a valid target-specific
   technical-account `MY_ITMO_REFRESH_TOKEN` in this staged private environment
   **before the first backend start**: a fresh database cannot supply one and the
   startup catalog request can fail before any HTTP health check. Do not copy the
   seed between environments or wait until the smoke-test phase to configure it.
   Do not overwrite the active files.
6. Create the selected **new** PostgreSQL directory and verify it is empty and
   distinct from every MariaDB mount. The Compose bind deliberately refuses to
   create a missing host directory. Do not copy MariaDB files into it.
7. Install a private copy of that environment's Firebase key at
   `FIREBASE_KEY_FILE`, readable by UID/GID 10001 and no unrelated users (for
   example owner 10001:10001 and mode 0400). Preserve the old key for rollback.
   Validate staged configuration using `docker compose config --quiet`.

### 2. Freeze and back up the old installation

Run on the target server in a Bash shell with `set -euo pipefail` and `umask 077`.
Stop the **old application service only**, using the inspected old Compose file;
keep MariaDB running long enough to dump it. This prevents new writes during the
cutover snapshot. Back up the old Compose/Dockerfile, `.env`, exact JAR and image
IDs in a timestamped mode-0700 directory under `/mnt/raid/backups`, outside either
Docker build context. Preserve old images with a rollback tag or `docker image
save`; do not rely on a mutable image tag remaining unchanged.

For the inspected target, set `OLD_DB_CONTAINER` and `BACKUP_DIR` to that
container and protected backup directory. The following dumps credentials only
inside the existing container environment; the dump itself is sensitive:

```bash
docker exec "$OLD_DB_CONTAINER" sh -ec '
  export MYSQL_PWD="${MARIADB_ROOT_PASSWORD:-${MYSQL_ROOT_PASSWORD:?Missing administrator password}}"
  exec mariadb-dump --user=root --single-transaction --routines --events \
    --triggers --databases "${MARIADB_DATABASE:-${MYSQL_DATABASE:?Missing database name}}"
' | gzip > "$BACKUP_DIR/mariadb.sql.gz"
test -s "$BACKUP_DIR/mariadb.sql.gz"
gzip -t "$BACKUP_DIR/mariadb.sql.gz"
```

Check the dump restores into an isolated compatible MariaDB instance with no
application attached. A nonempty compressed file alone is not a verified restore.
Do not download production dumps into the source tree, use them for application
testing, or print their contents. Keep old MariaDB bind data untouched.

### 3. Replace the stack, not the data directory

1. Stop/remove the old Compose containers and its private network **without any
   volume removal or data deletion**; keep the external `web` network intact.
   Record its project name before removal. The reused container names and host
   ports require the old containers to be gone before starting PostgreSQL.
2. Move active deployment files to the protected rollback directory, then install
   the staged configuration and tested artifact. Ensure old Compose auto-detected
   filenames do not remain alongside the replacement `compose.yaml`.
3. Confirm the staged technical seed and readable Firebase file are ready before
   starting `backend`; PostgreSQL readiness does not establish upstream credential
   readiness. From the target deployment directory, run:

```bash
docker compose --env-file .env -f compose.yaml config --quiet
docker compose --env-file .env -f compose.yaml up -d --wait database
docker compose --env-file .env -f compose.yaml logs --tail=100 database
docker compose --env-file .env -f compose.yaml up -d --build backend
docker compose --env-file .env -f compose.yaml ps
docker compose --env-file .env -f compose.yaml logs --tail=200 backend
```

4. Check PostgreSQL role initialization, Flyway V1 success, Hibernate validation,
   and clean application startup. Inspect `flyway_schema_history` with the local
   check above adapted to `-f compose.yaml --env-file .env`, without `-p
   itmowidgets-local`. Verify no users/friendships/queues were imported.
5. Smoke-test both anonymous version endpoints at the target public URL; verify
   `latestVersion` agrees with the legacy string. `minVersion` and latest should
   be `2.1` unless the separately approved release configuration says otherwise.
6. On development, verify authentication, device registration, privacy allowed and
   denied paths, schedule/sport requests, and queue lifecycle with explicit test
   accounts. Do not mutate production data for tests. Production checks are
   read-only health/migration checks plus observing authorized real-user traffic.
7. Verify that the initial catalog refresh succeeded and the technical credential
   is persisted without displaying its value. Remove the one-time
   `MY_ITMO_REFRESH_TOKEN` override from `.env` and recreate `backend`. The seed-only
   bootstrap must preserve the rotated stored credential even if an old override
   was left in place. Verify
   catalog refreshes and delayed scheduler/FCM/JPA logs; a successful version
   endpoint alone is not service readiness.
8. Restart the new application and verify there is no second schema creation,
   lost data, pending migration, or stale token bootstrap. Keep the maintenance
   window until checks pass; report exactly what was verified.

### 4. Rollback during the cutover window

Rollback restores the old **application and MariaDB stack together**, never an
old JAR against PostgreSQL. If checks fail before normal traffic resumes:

1. Stop the new application, then stop/remove the new Compose containers without
   deleting PostgreSQL data. Keep that directory for diagnosis.
2. Restore the saved old Compose configuration, `.env`, JAR, exact application
   image and original MariaDB mount references. Preserve file permissions.
3. Start the old MariaDB and application services, then verify logs and the old
   version endpoint. The stopped old MariaDB directory remains the primary
   rollback source; a dump restore is a separate controlled recovery operation.

After new user writes have been accepted, rollback would lose those new
PostgreSQL records. Stop and agree a data/recovery decision before reverting;
there is no reverse importer. Do not silently reconnect users to the stale DB.

## Ongoing PostgreSQL backups

Before a schema deployment and on the selected backup schedule, run on the
server with `umask 077`. Set `BACKUP_FILE` to a timestamped file under the protected
backup directory. Use the installed target Compose file:

```bash
docker compose --env-file .env -f compose.yaml exec -T database sh -ec \
  'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' \
  > "$BACKUP_FILE"
test -s "$BACKUP_FILE"
docker compose --env-file .env -f compose.yaml exec -T database \
  pg_restore --list < "$BACKUP_FILE" > /dev/null
```

Protect the environment file and role bootstrap material separately: `pg_dump`
backs up one database, not cluster roles or their passwords. Periodically restore
a backup into a separate PostgreSQL cluster, initialize the application role,
verify Flyway history and Hibernate validation, and document the restore result.
Never overwrite the running database to test a backup. Restrict access and define
backup retention before opening the PostgreSQL deployment to normal traffic.
