# Deployment log

Newest first. Hashes and tags are the rollback material.

## 2026-10-03 — production, advertised app version 2.2

- `.env` `APP_VERSION=2.1.1` → `2.2` (`MIN_APP_VERSION` stays `2.1`; no
  `app.*` rows in `app_settings`), `backend` recreated on the same `v1.7.0`
  image. `GET /api/app/version-info` returns `latestVersion 2.2`,
  `minVersion 2.1`; no `ERROR` in the logs. GitHub release `v2.2` carries
  `itmo-widgets-v2.2.apk`. Previous `.env`:
  `/mnt/raid/backups/archive/itmowidgets-env-appversion-20261003.tar.gz`.

## 2026-10-03 — production, Backend 1.7.0, AI summaries and reviews sync

- Backend `v1.7.0` (`0f42920`, Core 1.7.0 and MyItmoApi 1.8.2 from Maven
  Central), deployed by
  [release 37071130245](https://github.com/alllexey-dev/itmo-widgets-backend/actions/runs/37071130245)
  after the owner approved `production`; the first production deploy through
  `platform deploy`. Previous image
  `itmowidgets-backend:groups-1.2.1-20260921T074412Z`. Image digest:
  `sha256:7379cd66e4395d84445d3e905102ff65f0b241866f1616ac2b95c9761bd34c5d`.
  Validated pre-deploy backup:
  `/mnt/raid/backups/deploys/itmowidgets/20261002T221529Z-v1.7.0.dump`.
- Flyway applied V4–V10 in 0.11 s. Smoke: `GET /api/app/version-info` 200
  (still 2.1.1 / 2.1), `GET /api/friends` 403, anonymous
  `POST /api/web/auth/challenges` 200.
- Then production moved to the repository `deploy/compose.yaml` with the
  `itmowidgets-gemini-proxy` sidecar (config copied from development),
  `AI_SUMMARY_ENABLED=true`, `REVIEWS_SYNC_ENABLED=true` and the development
  Gemini model and budget; `backend` recreated, no `ERROR` in the logs.
  `GEMINI_API_KEY` and `ISU_KEYCLOAK_IDENTITY` stay `MISSING` until the owner
  enters them in the web admin. `MY_ITMO_REFRESH_TOKEN` is `OK`; its `.env`
  seed can be removed.
- The owner (ISU 502587) got `ADMIN` by the SQL in `moderation.md`. The site
  and the web app at `/app/` were deployed to production the same day.
- Rollback: `platform rollback itmowidgets` (image only; 1.2.1 ignores
  V4–V10). The previous compose and `.env` are in
  `/mnt/raid/backups/archive/itmowidgets-compose-env-20261003.tar.gz`.

## 2026-09-28 — development, person-profile review reads

- Backend `0959f2749ce7f50b0875eacc68c942c4e90b6561`, image
  `ghcr.io/alllexey-dev/itmo-widgets-backend:sha-0959f2749ce7`, deployed by
  [deliver 36450389070](https://github.com/alllexey-dev/itmo-widgets-backend/actions/runs/36450389070).
  Build/test, deploy-dev and promotion passed; platform history records success
  at 16:24:40 UTC. Image digest:
  `sha256:cf6e03f3722d82e89e5012a8e738511fc9fdf556f868474a8f2c28bce858b9e0`.
- Previous dev image: `sha-5f4ae5c5228a`. Validated pre-deploy database backup:
  `/mnt/raid/backups/deploys/itmowidgets-dev/20260928T162428Z-sha-0959f2749ce7.dump`.
  No new migration. Rollback: `platform rollback itmowidgets-dev` (image only).
- Smoke: anonymous `GET /api/app/version-info` returned 200;
  `GET /api/teachers/100001/reviews` returned 403. Local Backend build passed
  614 tests. Read-only active-review statistics (ISU / count / longest text):
  `257667 / 18 / 1292`, `366009 / 17 / 560`, `362709 / 16 / 793`.
- `platform doctor` before and after: 0 failures, the same 2 warnings
  (degraded RAID and general backups not configured). Production image and
  uptime were unchanged: `itmowidgets-backend:groups-1.2.1-20260921T074412Z`.
- Android, Core and MyItmoApi were committed locally, not pushed. This log
  entry is a subsequent local-only commit, not another deployment.

## 2026-09-24 — development, reviews sync

- Backend 1.7.0-SNAPSHOT at `f319b08`, image `itmowidgets-dev-backend:reviews-20260924T154024Z`,
  jar SHA-256 `a81f262535d7840c03ed38767c9934b956d554e729b49fb82c871e11282fe2a0` (594 tests green). Backup
  `/mnt/raid/backups/itmowidgets-dev-reviews-20260924T154024Z` (jar, env, Compose/Dockerfile,
  `pg_dump` checked with `pg_restore --list`, previous web directory); previous image tagged
  `itmowidgets-dev-backend:pre-reviews-20260924T154024Z`. `compose.yaml` replaced with the repository
  version, `.env` got `REVIEWS_SYNC_ENABLED=true`. Flyway applied `V7__external_teacher_reviews`
  (history 1–7), no `ERROR` lines. Smoke: `version-info` 200, `/api/admin/reviews/sync` 403 anonymously.
- Web app: `git archive` of `itmo-widgets-web` `6834fd7`; `/app/admin/reviews` 200.
- First run started from the admin: `UPDATED`, 347 teachers, 1290 reviews in about 9.5 minutes.
  The scheduled run at 05:00 Moscow time got 304 (`UNCHANGED`). 18 reviews had a single-digit
  hour in the date and stayed undated; fixed later in `c903155`.

## 2026-09-24 — development, saved links dropped

- Backend 1.7.0-SNAPSHOT at `ac0b8b2`, image `itmowidgets-dev-backend:dropsaves-20260924T113751Z`,
  jar SHA-256 `930f897d70dbc40350142374d78ac8fcfe8fa1db7472d7caf1b8983b2a88e44a` (562 tests green). Backup
  `/mnt/raid/backups/itmowidgets-dev-dropsaves-20260924T113751Z` (jar, env, Compose/Dockerfile,
  `pg_dump` checked with `pg_restore --list`); previous image tagged
  `itmowidgets-dev-backend:pre-dropsaves-20260924T113751Z`. `subject_link_saves` held 0 rows;
  Flyway applied `V6__drop_subject_link_saves` (history 1–6), no `ERROR` lines.
  Smoke: `version-info` 200, `/app/` 200, links route 403 anonymously.

## 2026-09-24 — development, web sessions, admin API and the web app at /app/

- Backend 1.7.0-SNAPSHOT at `32194b6`, image `itmowidgets-dev-backend:web-20260924T092026Z`,
  jar SHA-256 `8d4d9aee9134a9af5e9113470afd334d92cb4196857d76b657677f6bde5be787`
  (560 tests green). Backup `/mnt/raid/backups/itmowidgets-dev-web-20260924T092026Z` (jar,
  env, Compose/Dockerfile, `pg_dump` checked with `pg_restore --list`); previous
  image tagged `itmowidgets-dev-backend:pre-web-20260924T092026Z`. Flyway applied
  `V5__web_sessions_and_admin` (history 1–5), no `ERROR` lines; anonymous
  `POST /api/web/auth/challenges` 200, `/api/admin/dashboard` and
  `/api/web/auth/me` 403.
- `ADMIN` granted on development to ISU 502587 by SQL (`user_roles`).
- Web app: `git archive` of `itmo-widgets-web` `00a390a` in
  `/mnt/raid/srv/web/itmowidgets-web-dev` (not a git clone yet; see
  `DEPLOYED_FROM`), container `itmowidgets-web-dev` built on the server from
  `compose.dev.yml`. The first build attempt coincided with a server network
  outage and never finished; the second took seconds.
- nginx-hub `conf.d/dev.widgets.alllexey.dev.conf` got `location /app/` →
  `itmowidgets-web-dev:80` before `location /` (backup
  `nginx-hub/backups/dev.widgets.alllexey.dev.conf.pre-app-20260924T101511Z`), `nginx -t`
  ok, reload. `/app/`, `/app/login`, `/app/admin/moderation` 200; hashed assets
  cached immutable for a year; the login page creates live codes. Production
  unchanged (`/` and `/api/app/version-info` 200).

## 2026-09-24 — production, landing container renamed to itmowidgets-web

- The landing repository was renamed on GitHub to `alllexey-dev/itmo-widgets-web`
  (commit `1d7cc58` renames the Compose service and container). A fresh clone in
  `/mnt/raid/srv/web/itmowidgets-web` started container `itmowidgets-web` next to
  the old one; `nginx-hub/conf.d/widgets.alllexey.dev.conf` now proxies `/` to
  `itmowidgets-web:80` (`nginx -t` ok, reload), backup
  `nginx-hub/backups/widgets.alllexey.dev.conf.pre-rename-20260924T081112Z`.
- The old `itmowidgets-site` container was removed after traffic reached the new
  one; its checkout moved to `/mnt/raid/backups/itmowidgets-site-pre-rename-20260924T081112Z`.
  `/`, `/privacy.html` and `/api/app/version-info` answered 200 throughout.
  Backend and databases were not touched.

## 2026-09-24 — development, one schedule flow per link

- Backend 1.7.0-SNAPSHOT at `b073f6c`, image `itmowidgets-dev-backend:flows-20260924T061937Z`,
  jar SHA-256 `686121e29d3618c14d226ce9becbe46bece687592fdd606d40b957094fb8c2ad`
  (513 tests green). Backup `/mnt/raid/backups/itmowidgets-dev-flows-20260924T061937Z`
  (jar, env, Compose/Dockerfile, `pg_dump` checked with `pg_restore --list`);
  previous image tagged `itmowidgets-dev-backend:pre-flows-20260924T061937Z`.
- The rewritten V4 (visibility `PRIVATE|FLOW|ALL` with `flow_id`, no audience
  table) replaced the one from 2026-09-23. With the backend stopped, the existing
  rows (2 links with 2 revisions, 16 user subject flows, 6 moderation cases, 1
  decision) were parked in a `keep_links` schema, the V4 tables and history row
  dropped, the new V4 applied on startup (8.9 s, no `ERROR`), and the rows
  restored in one transaction: the old FLOW audience became `flow_id` 93724.
- Smoke: `version-info` 200, `/api/subjects/1/links` 403 anonymously.
  Production was not changed.

## 2026-09-23 — development, subject links

- Backend 1.7.0-SNAPSHOT at `26af8ad`, image `itmowidgets-dev-backend:links-20260923T194202Z`,
  jar SHA-256 `46a12119ecc484a704dbdafc754d16aceffffd89facb20ea230af0a070b79e3d`
  (512 tests green before the build).
- Backup `/mnt/raid/backups/itmowidgets-dev-links-20260923T194202Z`: previous jar, `.env`,
  Compose/Dockerfile and a custom `pg_dump` checked with `pg_restore --list`.
  Previous image tagged `itmowidgets-dev-backend:pre-links-20260923T194202Z`.
- The replaced resource tables of the old V4/V5 were empty (11 tables, 0 rows).
  With the backend stopped, one `psql` transaction dropped them and deleted the
  `flyway_schema_history` rows for versions 4 and 5; startup then applied the new
  `V4__subject_links` (history 1–4, all successful), started in 8.7 s with no
  `ERROR` lines.
- Smoke: `version-info` 200; `/api/subjects/1/links` and `/api/moderation/cases`
  403 anonymously. Production was not changed.

## 2026-09-23 — development, personal links and community resources

- Backend 1.3.0-SNAPSHOT from the uncommitted working tree based on
  `1c5a1422bcdfae600784ed595e961f501464e981`; no new commit or push.
  Image `itmowidgets-dev-backend:resources-20260923T124554Z`, jar SHA-256
  `43933b4a1ceb576226e63eef0c175303eee27266f96b96682f729db7b119783d`.
- Restarted development; V4 and V5 applied, V1–V5 successful in
  `flyway_schema_history`. Hibernate initialized and application startup
  completed with zero ERROR entries.
- Backup `/mnt/raid/backups/itmowidgets-dev-resources-20260923T124554Z` contains
  the previous jar, environment, Compose/Dockerfile and custom PostgreSQL dump.
  The archive was listed and successfully restored into a separate temporary
  PostgreSQL 17 cluster with no network or host ports before the migration;
  that temporary container and its anonymous volume were removed.
  Previous image tagged `itmowidgets-dev-backend:pre-resources-20260923T124554Z`.
- Version-info returned 200; subject resources, personal submission history,
  own resources/restrictions and moderation cases/settings all returned 403
  anonymously. Authenticated smoke was not run: no explicit dev test session
  was supplied. Local verification: Backend 492 tests, Core 63, Android 613,
  plus Android lint/build and emulator visual/instrumentation checks.
- Core 1.3.0-SNAPSHOT published to Maven Local only. At the user's request,
  the dev/debug Android build was installed through ADB over the existing app
  with the matching project signature; app data was preserved.
- Production, its routing and credentials were not changed. No Git tag,
  public artifact publication or production release was performed.

## 2026-09-21 — production, advertised app version 2.1.1

- No new build: `APP_VERSION=2.1.1` in the production `.env`
  (`MIN_APP_VERSION` stays `2.1`), `backend` recreated on the same image
  `itmowidgets-backend:groups-1.2.1-20260921T074412Z`. `GET /api/app/version`
  returns `2.1.1`, `version-info` reports `latestVersion 2.1.1`, no `ERROR`
  lines. Android release `v2.1.1` (`itmo-widgets-v2.1.1.apk`) is on GitHub and
  `releases/latest` resolves to it, so 2.1 clients get the update offer.

## 2026-09-21 — production, current groups in every profile response

- Backend `770293b` (1.2.1), image `itmowidgets-backend:groups-1.2.1-20260921T074412Z`,
  jar SHA-256 `317d8347b78928866c416da5f61f0155740b25361fc344e3c90bcd646452bc28`.
- No schema change; Flyway validated 3 migrations, started in 8.4 s, no `ERROR`
  lines. `version-info` 200; `/api/friends` and
  `/api/schedule/lessons/{pairId}/friends` 403 anonymously.
- Previous jar, `.env` and a `pg_dump` in
  `/mnt/raid/backups/itmowidgets-prod-groups-20260921T074412Z`; previous image
  tagged `itmowidgets-backend:pre-groups-20260921T074412Z`.

## 2026-09-21 — production, development data imported; development stopped

- No new build: the running image `itmowidgets-backend:release-2.1-20260920T2010Z`
  was kept and the import ran as one `psql` transaction against the live
  database (dry run with `ROLLBACK` first, then the same file with `COMMIT`).
- Imported from development, keyed by ISU so the one user present in both kept
  the production row: 7 users with `user_settings` and `user_groups`, 5 groups,
  9 `ACCEPTED` friendships, 2 active `sport_free_sign_entries`, 16
  `user_sport_lessons` whose lesson exists in the production catalog, and 191
  future `lessons` cache rows. Not imported: `devices` (tokens of debug
  installs), `my_itmo_storage`, the sport catalog, `sport_update_logs`,
  cancelled or finished queue entries, past cache rows.
- After the import: 8 users, 6 groups, 9 friendships, 2 active queues, 17
  bookings, 221 cache rows; every user has settings and a group. Version
  endpoint 200, social routes 403 anonymously, no `ERROR` lines.
- Backup `/mnt/raid/backups/itmowidgets-prod-pre-devimport-20260920T222016Z`
  (mode 0700): `prod.dump` and `dev.dump` (`pg_dump --format=custom`, the
  production one checked with `pg_restore --list`), the exported TSV files and
  the exact `import.sql`.
- Development stack (`itmowidgets-dev`, `itmowidgets-dev-db`) stopped with
  `docker compose stop`; containers, volumes and
  `/mnt/raid/srv/dbs/itmowidgets-dev-postgres` are kept.
  `https://dev.widgets.alllexey.dev` now answers 502 from nginx.

## 2026-09-20 — production, PostgreSQL cutover

- Backend `4dac55c` (1.2.0 on Core 1.2.0), image
  `itmowidgets-backend:release-2.1-20260920T2010Z`, jar SHA-256
  `c5285a7e4b569d68d9ef5649f9b8bf2ade2c6b098cb6a178e6593bb454d878d2`.
- New empty PostgreSQL 17 cluster in `/mnt/raid/srv/dbs/itmowidgets-postgres`;
  no MariaDB data imported (user decision). V1–V3 applied on first start; the
  technical credential was seeded, persisted to `my_itmo_storage` and the seed
  removed from `.env` afterwards (`.env.bak-seed-20260920T2010Z` kept next to
  it, mode 600); `backend` recreated without the seed and validated the three
  migrations again. First catalog refresh `PARTIAL`: 1543 received, 1528
  stored, 15 skipped with the known `MAPPING` category.
- `APP_VERSION=2.1`, `MIN_APP_VERSION=2.1`: `/api/app/version` and
  `/api/app/version-info` return `2.1`, social routes 403 anonymously, no
  `ERROR` lines at startup. Legacy 2.0.x clients are rejected from now on and
  are pointed at the GitHub release by their update dialog.
- Backup `/mnt/raid/backups/itmowidgets-prod-20260920T2010Z` (mode 0700):
  `mariadb-dump --single-transaction --routines --events --triggers` of
  `itmo_widgets` (17 tables, restored and counted in an isolated container:
  127 users, 105 auto-sign entries; the phantom
  `sport_update_logs_new_lessons`, present in the catalog but absent from the
  engine and empty, was excluded) plus `old-deployment/` with the previous
  compose file, `.env`, jar and Firebase key. The old deployment directory is
  `/mnt/raid/srv/web/itmowidgets-mariadb-20260920T2010Z`, the MariaDB data
  directory `/mnt/raid/srv/dbs/itmowidgets-db` is untouched; rollback images
  `itmowidgets-backend:rollback-mariadb-20260920T2010Z` and
  `itmowidgets-mariadb:rollback-20260920T2010Z`.
- The old application had been failing its sport refresh every minute
  (`TokenRefreshException: no refresh token present`) since at least the day
  before, so production sport automation was already down before the cutover.
- Deleting the MariaDB stack and backup is a separate decision.

## 2026-09-17 — development, current study groups

- Backend `093c171`, image `itmowidgets-dev-backend:current-groups-20260916T222000Z`,
  jar SHA-256 `a6e8969dc5d205e7da51eace2cca679dfeb5b060aa929aaddc7dec4125aed49b`.
- Jar-only release: V1–V3 validated unchanged; no user records or schema were
  rewritten. Current group resolution is limited to friend-list and profile GETs.
- Previous jar and image reference are in
  `/mnt/raid/backups/itmowidgets-dev-current-groups-20260916T222000Z`;
  rollback image `itmowidgets-dev-backend:pre-current-groups-20260916T222000Z`.
- All 437 Backend tests passed, including PostgreSQL tests. Startup had no ERROR
  entries; version endpoint returned 200 and the four protected reads returned
  403 anonymously.
- Authenticated, read-only verification compared all three friend profiles with
  official MyITMO education: friend-list groups, public profiles and the self
  friends-list route agree. No personal data was copied into this log.
- Core, MyItmoApi and Android binaries were unchanged; production was not touched.

## 2026-09-16 — development, friends privacy

- Backend `79da56a`, Core `28bcb92`; image
  `itmowidgets-dev-backend:friends-privacy-20260916T150800Z`, jar SHA-256
  `c17f66606fe8815f09e49715cff69bfcb54d5405229cbc8c2e87984317c93707`.
- V3 applied; friends default to ALL, schedule and sport choices preserved.
- Backup `/mnt/raid/backups/itmowidgets-dev-friends-privacy-20260916T150800Z`: old
  jar and custom PostgreSQL dump, successfully restored in an isolated temporary
  PostgreSQL container before migration. Previous image also tagged
  `itmowidgets-dev-backend:pre-friends-privacy-20260916T150800Z`.
- Version endpoint 200, social routes 403 anonymously, authenticated read-only
  privacy/self/friends requests and strict Core decoding passed. Backend started
  with no ERROR entries. Production unchanged.

## 2026-09-17 — development, empty unpublished name

- Backend `bfcad22`, image `itmowidgets-dev-backend:emptyname-20260917T111435Z`,
  jar SHA-256 `7f0d28e07439f0e100fb0031525858365eee7ce84436912925e126ec325a73f3`.
- No schema change; Flyway validated V1 and V2. Version endpoint 200, social
  routes 403 anonymously.
- Previous jar kept as `itmo-widgets-backend.pre-emptyname-*.jar` in the
  friendships backup directory; previous image tag `devices-20260916T095301Z`.

## 2026-09-16 — development, device-gated attempts

- Backend `c1fb961`, image `itmowidgets-dev-backend:devices-20260916T095301Z`,
  jar SHA-256 `5f62b4e1f6cac5e7096af01264ea21ed66612c6d0c70d1582cfd64bff6c117b1`.
- No schema change; Flyway validated V1 and V2. Version endpoint 200, social
  routes 403 anonymously.
- Previous jar kept as `itmo-widgets-backend.pre-devices-*.jar` in the
  friendships backup directory; previous image tag `fcm-20260916T212740Z` retained.
- Entries already in `GAVE_UP_NOTIFYING` are not revived; owners re-create them.

## 2026-09-20 — development, friends on a lesson

- Backend `bae9d60`, image `itmowidgets-dev-backend:lessonfriends-20260920T100759Z`,
  jar SHA-256 `47a563440ddf8a41c3989b8ac7f11f1b3b6f6dd09ab851d2b698ba3b4dac0ffc`.
- No schema change; Flyway validated 3 migrations. Version endpoint 200, the
  new `/api/schedule/lessons/{pairId}/friends` and the removed `/users` route
  both 403 anonymously, no errors in the log.
- Previous jar and `.env` kept in `/mnt/raid/backups/itmowidgets-dev-lessonfriends-20260920T100759Z`;
  previous image tagged `pre-lessonfriends-20260920T100759Z`.

## 2026-09-19 — development, MyITMO seed removed

- No new build: the running image was kept and `backend` was recreated after
  deleting `MY_ITMO_REFRESH_TOKEN` from `.env` (backup of `.env` left next to it
  with mode 600). `my_itmo_storage` already held the rotated technical refresh
  token, so startup had nothing to seed; compose now passes the variable empty.
- Flyway validated 3 migrations, `version-info` 200, no `ERROR` lines. The
  startup `Sport lesson mapping failed` warnings are the known catalog ones.
- The 2026-09-16 follow-up below is closed.

## 2026-09-16 — development, FCM revision

- Backend `cf48da6`, image `itmowidgets-dev-backend:fcm-20260916T212740Z`,
  jar SHA-256 `d64c4697fb7162f4a20bf52878b81f94f96999c54ad510346a3bc8a47f8c573f`.
- No schema change; Flyway validated V1 and V2. Version endpoint 200, social
  routes 403 anonymously, no errors in the log.
- Previous jar kept as `itmo-widgets-backend.pre-fcm-*.jar` in the friendships
  backup directory; previous image tag `friendships-20260915T154045Z` retained.
- Follow-up: the `MY_ITMO_REFRESH_TOKEN` seed is still in `.env`; remove it and
  recreate `backend`.

## 2026-09-15 — development, friendships revision, database recreated

- Backend `dd37d69`, image `itmowidgets-dev-backend:friendships-20260915T154045Z`,
  jar SHA-256 `84c3af8bbddd1cb87026a7b5bce211979dcc43bedb42da541ea209359f68d2ef`.
- The PostgreSQL cluster was recreated empty (user decision) so that the deployed
  V1 matched the repository; V1 (checksum `2141784825`) and V2 (`1413684701`)
  applied on first start. The technical credential was re-seeded and persisted;
  the catalog refreshed to 1612 lessons with the known building-mapping warnings.
- Backup `/mnt/raid/backups/itmowidgets-dev-friendships-20260915T154045Z`
  (pg_dump of the previous database, `env.pre-friendships`, old jar, compose
  files); previous data directory kept as
  `/mnt/raid/srv/dbs/itmowidgets-dev-postgres-pre-friendships-20260915T154045Z`;
  previous image tag `pre-friendships-20260915T154045Z`.

## 2026-09-09 — development, PostgreSQL cutover

- Backend `f46c548` and a later same-day venue fix (image
  `locations-20260909T153126Z`), Core `dee6857`, both `1.2.0-SNAPSHOT`.
- New empty PostgreSQL 17 cluster; no MariaDB data imported. Twelve anonymous
  requests to protected routes were denied; both version endpoints returned `2.1`.
- Catalog refresh was `PARTIAL`: 272 of 1639 lessons skipped for missing or
  unlisted building IDs, later fixed by keeping raw venues.
- Protected backup `/mnt/raid/backups/itmowidgets-dev-20260909T144940Z` with the
  verified MariaDB dump; MariaDB volume `itmowidgets-dev_db-data` preserved;
  rollback tags `itmowidgets-dev-backend:rollback-20260909T144940Z` and
  `itmowidgets-dev-mariadb:rollback-20260909T144940Z`.

## Production

Runs Backend 1.2.0 on PostgreSQL 17 since 2026-09-20 (entry above). Before that
Backend 1.1.6 on MariaDB, verified 2026-09-08.
