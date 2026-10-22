# Deployment

Backend runs on `alllexey.dev` in Docker Compose behind the shared Caddy edge
(`stacks/edge/Caddyfile` in the server's `srvscripts` repository, `/srv/platform`).

## Delivery pipeline

Development is deployed by CI; production only after a separate approval.

1. Work happens on `dev`. Every push to `dev` runs `.github/workflows/deliver.yml`:
   `./gradlew build`, the image
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
is the fallback.

## Where to look

Nothing in this repository records deployments; these systems do.

| Question | Where |
|---|---|
| Which release runs in production, and when it was released | `gh release list` (each release is created only after a successful production deployment) |
| Which images a stack ran, when, and whether a deploy rolled back | `ssh alllexey.dev platform history itmowidgets` (or `itmowidgets-dev`) |
| What runs right now and whether it is healthy | `ssh alllexey.dev platform status itmowidgets` (or `itmowidgets-dev`) |
| Which commit went to development and its build, deploy and promotion | `gh run list --workflow deliver.yml` and the run's summary |
| The pre-deploy backup of a deployment | `/mnt/raid/backups/deploys/<stack>/` on the server, named by time and tag |
| Which migrations a database applied | `flyway_schema_history`, read as in [database](database.md#inspecting-the-migration-history) |
| What changed in a release | [`CHANGELOG.md`](../../CHANGELOG.md) |

A manual step outside `platform deploy` (an `.env` change, a sidecar, an
operation from this page) is noted in the issue or PR that asked for it.

## Environments

| | Development | Production |
|---|---|---|
| URL | `https://dev.widgets.alllexey.dev` | `https://widgets.alllexey.dev` |
| Directory | `/mnt/raid/srv/web/itmowidgets-dev` | `/mnt/raid/srv/web/itmowidgets` |
| Compose project | `itmowidgets-dev` | `itmowidgets` |
| Containers | `itmowidgets-dev`, `itmowidgets-dev-db` | `itmowidgets`, `itmowidgets-db` |
| Host DB port (loopback) | 3301 | 3302 |
| PostgreSQL data | `/mnt/raid/srv/dbs/itmowidgets-dev-postgres` | `/mnt/raid/srv/dbs/itmowidgets-postgres` |

Development runs the head of `dev`; `version` in `build.gradle.kts` stays the
last released number between releases. Flyway applies pending migrations on
start; the migrations themselves and their rules are in
[database](database.md#migrations). Look up what runs where as in
[Where to look](#where-to-look); never infer it from source changes.
The Caddy edge routes `/api/*` of each domain to its backend container on port
8080 and everything else to the web container (see
[the web version](#web-version-and-the-app-route)); do not change that routing
as part of a release. `.env` and the Firebase key are private server
files; never copy them between environments. The Docker CLI on the server does
not publish the loopback DB port for the internal network: use
`docker compose --env-file .env -f compose.yaml exec -T database` for `psql` and
`pg_dump`. Backups live under `/mnt/raid/backups/`, mode 0700, outside any build
context.

Smoke endpoints: `GET /api/app/version-info` (200 anonymously), any social
route (401 anonymously), `POST /api/pair/challenges` (400 anonymously without a body),
`GET /api/admin/dashboard`, `GET /api/admin/reviews/sync`,
`GET /api/admin/reviews/verification`, `GET /api/admin/system/credentials`,
`GET /api/teachers/{isu}/reviews`, `PUT /api/teachers/100001/reviews/mine`,
`GET /api/teachers/summary-levels?isu=100001` and
`GET /api/admin/reviews/summaries` (401 anonymously).

## Reviews sync

`compose.yaml` forwards `REVIEWS_SYNC_ENABLED` (default `false`) to the backend.
Each environment turns it on in its own `.env`. When enabled, the backend needs outbound
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

## AI summaries and `gemini-proxy`

The [AI summaries](ai-summaries.md) reach Gemini only through the
`gemini-proxy` sidecar of the same Compose project (image
`ghcr.io/xtls/xray-core:26.2.6`, container `GEMINI_PROXY_CONTAINER_NAME`, for
example `itmowidgets-dev-gemini-proxy`). Compose requires
`GEMINI_PROXY_CONTAINER_NAME` in `.env` for every command, including a
backend-only one. `platform deploy` recreates only `backend`, so the proxy is
started or updated by hand:

```bash
docker compose --env-file .env -f compose.yaml up -d gemini-proxy
```

It needs `gemini-proxy/config.json` next to `compose.yaml` (owner and group
65532, mode 600; the rules for writing it and the redacted copy in `srvscripts`
are in [AI summaries](ai-summaries.md#network-and-gemini-proxy)). Backend
starts without the proxy; only summary runs fail until it is up.

`compose.yaml` passes `GEMINI_PROXY_HOST=gemini-proxy` and
`GEMINI_PROXY_PORT=3128` itself and forwards from `.env`: `AI_SUMMARY_ENABLED`
(default `false`), `GEMINI_MODEL`, `AI_SUMMARY_DAILY_BUDGET` (default 0),
`AI_SUMMARY_REQUEST_DELAY` (default `10s`), `GEMINI_THINKING_BUDGET` (default
empty), `GEMINI_MAX_OUTPUT_TOKENS` (default 2048) and the seed `GEMINI_API_KEY`
(default empty). With `AI_SUMMARY_ENABLED=true` Backend refuses to start without
a model and a positive budget. Remove the seed after the first successful run
([service credentials](service-credentials.md#seeds)).

## Web version and the `/app/` route

The web version (login and admin) is built from the `web/` folder of the
`itmo-widgets-web` repository (the former site repository). One nginx container
serves the landing page at `/` and the single-page app at `/app/`; the browser
calls Backend on the same origin under `/api/`, so Backend needs no CORS and
the `iw_client` cookie (`Path=/api`) reaches only Backend.

- Development: a clone of `itmo-widgets-web` in
  `/mnt/raid/srv/web/itmowidgets-web-dev`, started with
  `docker compose -f compose.dev.yml up -d --build` as container
  `itmowidgets-web-dev` in the external `web` network. The Caddy edge routes
  `dev.widgets.alllexey.dev/api/*` to `itmowidgets-dev:8080` and everything
  else, `/app/` included, to `itmowidgets-web-dev:80`.
- Production: the same split for `widgets.alllexey.dev`, with `itmowidgets:8080`
  and the site container `itmowidgets-web:80`, which serves `/app/` too.
- The edge must keep setting `X-Real-IP` to the client address: the web login
  rate limit trusts that header only from a private-network peer.

On a new database, grant the first `ADMIN` by SQL
([moderation](moderation.md)); every later moderator is managed in the web
admin.

## Manual release procedure

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
6. A manual deployment does not appear in `platform history`: note the image
   tag and the backup in the issue or PR that asked for it.

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

The usual rollback is by the image: `ssh alllexey.dev platform rollback <stack>`,
or set `BACKEND_IMAGE` to the previous tag and recreate `backend`. Then check
the logs for `Schema ... is up to date` and no `ERROR`, and the smoke
endpoints. Keep applied migrations and their data; the next deployment of the
newer release uses them as they are.

This works because migrations are expand-only
([database](database.md#migrations)): the previous release's image validates
against the newer schema, its Flyway ignores applied migrations it does not
know, and Hibernate `validate` ignores extra columns. A release that cannot be
rolled back by the image (a dropped table the previous image still reads, a
changed column) says so in `CHANGELOG.md`; rolling it back means restoring the
pre-deploy dump into a new directory together with the old image. If users have
already written to the new schema, stop and decide what to do with that data
before restoring.
