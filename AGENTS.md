# ITMO.Widgets Backend agent guide

The ecosystem-wide rules are in the Android repository's `AGENTS.md`
(`/Users/alllexey/proj/ITMO.Widgets/AGENTS.md`). This file adds what is
specific to Backend. Documents in `docs/` describe the current state; history
goes to `CHANGELOG.md`.

## Responsibilities

Backend is the authority for users, friendships, privacy audiences, viewer
capabilities, sport queues and FCM delivery. It authenticates every request with
the ITMO.ID access token, stores only the ISU-derived identity, never the user's
refresh token, and never trusts the client to enforce access.

## Hard rules

- Spring Security denies by default; every new route has tests for the allowed
  and the denied path. Endpoints returning other users' data need an explicit
  authorization matrix.
- PostgreSQL 17, Flyway is the only schema writer, Hibernate validates. Applied
  migrations are immutable; a change is a new `V<n>__*.sql`
  (Android `docs/decisions/0002-immutable-v1.md`). Tests that pin the current
  migration count live in `PostgreSqlMigrationTest` and `BackendStartupTest`.
- Exactly one replica: schedulers take no distributed lock and the notification
  phase lives in process memory. Never scale `backend` in Compose.
- HTTP calls to MyITMO and FCM happen outside database transactions;
  notifications are delivered after commit and rechecked against current state.
- Logs and `sport_update_logs` carry no tokens, payloads or user data.
- Core is pinned to a released version (`coreVersion` in `build.gradle.kts`);
  during a coordinated cycle it may point at a `-SNAPSHOT` from Maven Local
  (Android `docs/decisions/0003-snapshot-versions.md`).

## Build and test

Java 21 and a Docker engine for Testcontainers. On this Mac Docker is colima:

```bash
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true \
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
```

Tests never touch `deploy/.env`, an external database or real credentials.
Do not skip the PostgreSQL tests when Docker is unavailable; start it.

## Deployment

Only on explicit request, development first. The procedure is in
`docs/ops/deployment.md`, the log in `docs/ops/deployments.md`. Stage the jar as
exactly `itmo-widgets-backend.jar`; back up before any schema change; never copy
`.env` or Firebase keys between environments.
