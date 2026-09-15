# Deployment log

Newest first. Hashes and tags are the rollback material.

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

Runs Backend 1.1.6 on MariaDB (verified 2026-09-08). The PostgreSQL cutover
waits for the Android 2.1 release.
