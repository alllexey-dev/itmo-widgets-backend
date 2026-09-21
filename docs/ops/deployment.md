# Deployment

Backend runs on `alllexey.dev` in Docker Compose behind the shared `nginx-hub`
reverse proxy. A deployment happens only on explicit request; development first,
production only after a separate approval.

## Environments

| | Development | Production |
|---|---|---|
| URL | `https://dev.widgets.alllexey.dev` | `https://widgets.alllexey.dev` |
| Directory | `/mnt/raid/srv/web/itmowidgets-dev` | `/mnt/raid/srv/web/itmowidgets` |
| Compose project | `itmowidgets-dev` | `itmowidgets` |
| Containers | `itmowidgets-dev`, `itmowidgets-dev-db` | `itmowidgets`, `itmowidgets-db` |
| Host DB port (loopback) | 3301 | 3302 |
| PostgreSQL data | `/mnt/raid/srv/dbs/itmowidgets-dev-postgres` | `/mnt/raid/srv/dbs/itmowidgets-postgres` |
| Stack | PostgreSQL 17 (since 2026-09-09) | PostgreSQL 17 (since 2026-09-20) |

Development is stopped since 2026-09-21 (`docker compose stop`, data kept);
start it again with `up -d` in its directory before the next development cycle.
Nginx proxies each domain to its app container on port 8080; do not change that
routing as part of a release. `.env` and the Firebase key are private server
files; never copy them between environments. The Docker CLI on the server does
not publish the loopback DB port for the internal network: use
`docker compose --env-file .env -f compose.yaml exec -T database` for `psql` and
`pg_dump`. Backups live under `/mnt/raid/backups/`, mode 0700, outside any build
context.

Smoke endpoints: `GET /api/app/version-info` (200 anonymously) and any social
route (403 anonymously).

## Release procedure (PostgreSQL environments)

1. Build and test locally: `./gradlew clean build bootJar` with Java 21 and
   Docker. Record the jar SHA-256 and the commit.
2. Upload the jar as `itmo-widgets-backend.jar.new` into the deployment
   directory and compare the hash.
3. Back up: copy the current jar into the backup directory, tag the current image
   (`docker tag <current> itmowidgets-dev-backend:pre-<change>-<timestamp>`),
   and for any schema change run `pg_dump --format=custom` plus
   `pg_restore --list` as shown below.
4. Switch: move the jar into place, set `BACKEND_IMAGE` in `.env` to a new tag,
   then `docker compose --env-file .env -f compose.yaml up -d --build backend`.
5. Verify: `ps`, backend logs for Flyway (`Successfully validated N migrations`
   or `Successfully applied`), no `ERROR`, the smoke endpoints, and for schema
   changes the `flyway_schema_history` rows. After the first start against a
   fresh database, confirm the technical credential persisted, then remove the
   `MY_ITMO_REFRESH_TOKEN` seed from `.env` and recreate `backend`.
6. Record the deployment in [deployments.md](deployments.md).

```bash
umask 077
docker compose --env-file .env -f compose.yaml exec -T database sh -ec \
  'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' > "$BACKUP_FILE"
test -s "$BACKUP_FILE"
docker compose --env-file .env -f compose.yaml exec -T database pg_restore --list < "$BACKUP_FILE" > /dev/null
```

`pg_dump` backs up one database, not roles or passwords; keep `.env` and the
role bootstrap material separately. Before a schema-affecting deployment, verify
the backup restores into a separate cluster.

## Rollback

For a jar-only release, set `BACKEND_IMAGE` back to the previous tag, restore
the previous jar and recreate `backend`. For a release with a migration,
application rollback is safe only when the old binary validates against the new
schema; otherwise restore the backed-up database into a new directory together
with the old application, and stop to agree on a data decision if users have
already written to the new schema. Never run an old MariaDB jar against
PostgreSQL.

## Production cutover from MariaDB

Done on 2026-09-20 (see [deployments.md](deployments.md)); kept as the record of
the procedure. It ran once, with a fresh PostgreSQL cluster and no data import:

1. Rehearse locally and on development; confirm Android 2.1 handles an empty
   backend (re-registration, FCM registration, default `FRIENDS` privacy,
   rebuilt sport queues).
2. Stage the tracked deployment files, a new private `.env` with the production
   row above, a new empty data directory, the Firebase key readable by UID 10001,
   and a valid production technical `MY_ITMO_REFRESH_TOKEN` seed.
3. Freeze the old application, dump MariaDB (`mariadb-dump --single-transaction
   --routines --events --triggers`), verify the dump restores in isolation, and
   preserve the old Compose files, `.env`, jar and images with rollback tags.
4. Remove the old containers without deleting volumes, install the staged files,
   start `database` with `--wait`, then `backend` with `--build`.
5. Verify Flyway, Hibernate validation, catalog refresh, both version endpoints
   and authenticated flows with explicit test accounts; never mutate production
   data for tests.
6. Keep the MariaDB stack and backup until the rollback window closes; deleting
   them is a separate decision.

Rollback during the window restores the old application and MariaDB together.
Once new PostgreSQL writes exist, reverting loses them; stop and decide.
