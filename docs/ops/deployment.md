# Deployment

Backend runs on `alllexey.dev` in Docker Compose behind the shared Caddy edge
(`stacks/edge/Caddyfile` in the server's `srvscripts` repository, `/srv/platform`).

## Delivery pipeline

Development is deployed by CI; production only after a separate approval.

1. Work happens on `dev`. Every push to `dev` runs `.github/workflows/deliver.yml`:
   `./gradlew build` (Core is built from source with `--include-build`), the image
   `ghcr.io/alllexey-dev/itmo-widgets-backend:sha-<12 hex>` is published, and the
   server runs `platform deploy itmowidgets-dev <tag>`.
2. `platform deploy` takes a `pg_dump` (checked with `pg_restore --list`, kept in
   `/mnt/raid/backups/deploys/<stack>/`), sets `BACKEND_IMAGE` in `.env`, recreates
   `backend` and waits until `/api/app/version-info` answers 200. If that does not
   happen within 180 s it switches back to the previous image.
3. When development is healthy, CI fast-forwards `master` to the same commit.
   A repository ruleset lets `master` move only to commits whose `deploy-dev`
   check succeeded; `v*` tags cannot be moved or deleted.
4. Production: `gh workflow run release.yml -f version=X.Y.Z` (or «Run workflow» in
   GitHub). The image already tested for the current `master` gets the tag `vX.Y.Z`
   and goes to `itmowidgets`; the git tag and the GitHub release appear only after a
   successful deployment.

On the server: `platform history itmowidgets-dev` lists deployments,
`platform rollback <stack>` returns to the previous image (it does not revert the
database; see [Rollback](#rollback)). CI reaches the server with a key that can
only run `platform deploy|status` for these two stacks. The manual procedure below
stays as the fallback and as the record of how the environments were built.

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

The development line is 1.7.0-SNAPSHOT. Subject links require the current V4
schema (see [database](database.md)); deployed images and migration history are
recorded in [deployments.md](deployments.md), not inferred from source changes.
Web sessions and the admin API need V5 (`V5__web_sessions_and_admin.sql`); back
up the database before the first start with it. The reviews sync needs V7
(`V7__external_teacher_reviews.sql`, new tables only). Service credentials need
V8 (`V8__service_credentials.sql`, copies the My ITMO tokens and keeps
`my_itmo_storage`) and own teacher reviews V9 (`V9__teacher_reviews.sql`).
Nginx proxies each domain to its app container on port 8080; do not change that
routing as part of a release. `.env` and the Firebase key are private server
files; never copy them between environments. The Docker CLI on the server does
not publish the loopback DB port for the internal network: use
`docker compose --env-file .env -f compose.yaml exec -T database` for `psql` and
`pg_dump`. Backups live under `/mnt/raid/backups/`, mode 0700, outside any build
context.

Smoke endpoints: `GET /api/app/version-info` (200 anonymously), any social
route (403 anonymously), `POST /api/web/auth/challenges` (200 anonymously),
`GET /api/admin/dashboard`, `GET /api/admin/reviews/sync`,
`GET /api/admin/reviews/verification`, `GET /api/admin/system/credentials`,
`GET /api/teachers/{isu}/reviews` and `PUT /api/teachers/100001/reviews/mine`
(403 anonymously).

## Reviews sync

`compose.yaml` forwards `REVIEWS_SYNC_ENABLED` (default `false`) to the backend.
Only development sets `REVIEWS_SYNC_ENABLED=true` in `.env`; production keeps
it off until a separate decision. When enabled, the backend needs outbound
HTTPS to `reviews.work.gd`. After the first start with it, an admin runs the
first sync from the web admin («Отзывы» → «Синхронизировать»), see
[reviews sync](reviews-sync.md).

## Service credentials and ISU

`compose.yaml` forwards two seeds to the backend: `MY_ITMO_REFRESH_TOKEN` and
`ISU_KEYCLOAK_IDENTITY` (both default empty). They are needed in `.env` only
while their row of `service_credentials` has no value, and are removed after
the first successful use ([service credentials](service-credentials.md#seeds)).
The backend needs outbound HTTPS to `isu.ifmo.ru` and `id.itmo.ru` for the
[ISU verification](isu-verification.md) of teacher reviews; without a cookie the
reviews simply stay unchecked.

## Web version and the `/app/` route

The web version (login and admin) is built from the `web/` folder of the
`itmo-widgets-web` repository (the former site repository). One nginx container
serves the landing page at `/` and the single-page app at `/app/`; the browser
calls Backend on the same origin under `/api/`, so Backend needs no CORS and
the `iw_session` cookie (`Path=/api`) reaches only Backend.

- Development: a clone of `itmo-widgets-web` in
  `/mnt/raid/srv/web/itmowidgets-web-dev`, started with
  `docker compose -f compose.dev.yml up -d --build` as container
  `itmowidgets-web-dev` in the external `web` network. The edge routes
  `dev.widgets.alllexey.dev/app/*` to `itmowidgets-web-dev:80` and everything
  else to the backend.
- Production: the site container `itmowidgets-web` already receives `/`, so it
  serves `/app/` too.
- `/api/**` keeps going to the backend container on port 8080. The edge must
  keep setting `X-Real-IP` to the client address: the web login rate limit
  trusts that header only from a private-network peer.

After the backend with V5 is up, grant the first `ADMIN` by SQL
([moderation](moderation.md)); every later moderator is managed in the web
admin.

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
   changes the `flyway_schema_history` rows. After the first start, check
   `service_credentials` with the query in
   [service credentials](service-credentials.md#storage-and-exposure), which
   never selects `value`; once the refresh token and the cookie are `OK`,
   remove the `MY_ITMO_REFRESH_TOKEN` and `ISU_KEYCLOAK_IDENTITY` seeds from
   `.env` and recreate `backend`.
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

A release with V8 and V9 rolls back only by the image (`platform rollback
<stack>`), without restoring the deployment dump: the previous image validates
against the new schema and still finds the My ITMO tokens in
`my_itmo_storage`. Its limits (the refresh token's expiry, the lost review
features) and the parking of `TEACHER_REVIEW` moderation cases in the schema
`rollback_parked` before the switch are in
[service credentials](service-credentials.md#image-only-rollback).

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
