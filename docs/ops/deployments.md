# Deployment log

Newest first. Hashes and tags are the rollback material.

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
