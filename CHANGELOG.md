# Changelog

## 1.2.0-SNAPSHOT (unreleased)

### 2026-09-16
- Sport queues no longer reserve notification attempts for owners without a
  registered device; such entries keep waiting instead of reaching
  `GAVE_UP_NOTIFYING` unheard.
- Friendship notifications: `FriendService` returns notification intents,
  `UserProfileService.act` delivers them after commit on a dedicated executor,
  the payload is rechecked against the current relationship. FCM messages carry
  `recipient_isu` next to the `data` envelope.

### 2026-09-15
- Explicit friendships: one row per pair with `PENDING`/`ACCEPTED`, routes
  `POST /api/friends/{isu}/request|accept|reject|cancel`, `DELETE /api/friends/{isu}`,
  `GET /api/friends`, `GET /api/users/{isu}`, `POST /api/users/lookup`.
  `V2__friendships.sql` converts legacy `friend_requests`.
- `GET /api/sport/users/{isu}/bookings` also returns pending queue entries.
- Development database recreated on V1 + V2; V1 declared immutable.

### 2026-09-09 — 2026-09-14
- PostgreSQL 17 with Flyway and Hibernate validation; Testcontainers suites for
  migrations, native mutations, startup and concurrency.
- Sport automation hardened: owner row locks, frozen forecast with match key,
  short transactions, after-commit best-effort delivery, safe refresh outcomes,
  technical log retention, online and external venues.
- Independent privacy audiences (`ALL`/`FRIENDS`/`NOBODY`) and viewer-scoped
  capabilities; `GET /api/app/version-info`.

## 1.1.6
- Last MariaDB release with reciprocal friend requests and boolean privacy.
