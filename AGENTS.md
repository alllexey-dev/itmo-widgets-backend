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

Java 21 and a Docker engine for Testcontainers. Build and test through
`scripts/verify.sh`: on this Mac it sets JDK 21, colima's `DOCKER_HOST` and
`TESTCONTAINERS_RYUK_DISABLED=true`, and it waits for the machine-wide backend
build slot, so only one Testcontainers build runs at a time.

```bash
scripts/verify.sh                            # ./gradlew build
scripts/verify.sh test '<test name pattern>' # ./gradlew test --tests <pattern>
scripts/verify.sh run -- <gradle args>       # ad hoc tasks, never --stop or publish
```

The last line is `VERIFY B <mode> PASS|FAIL <secs>s <sha7>[+dirty]`; the exit
code is 0 for pass, 1 for fail and 2 for a refused call.

CI runs `./gradlew build` as the `build` job of `.github/workflows/ci.yml` on
every pull request and every push to `v2.3/next`. Its first step fails when
`build.gradle.kts` names a `-SNAPSHOT`, so Backend always builds against a
released Core.

Tests never touch `deploy/.env`, an external database or real credentials.
Do not skip the PostgreSQL tests when Docker is unavailable; start it.

## Deployment

Work on `dev`: every push there is tested and deployed to development by
`.github/workflows/deliver.yml`, which then moves `master` to the same commit.
Never push to `master` or create `v*` tags yourself; CI owns them. Production is
released only on explicit request with the `release` workflow. The pipeline and
the manual fallback are in `docs/ops/deployment.md`; server-side history is
`ssh alllexey.dev platform history <stack>`. Never copy `.env` or Firebase keys
between environments.

## v2.3 lanes

For an agent executing a v2.3 lane card, the lane rules of `ITMO.Widgets/AGENTS.md` § v2.3 lanes
apply here too; until that section exists in the app repository, this block also overrides its
Git hygiene lines 115-116 and Definition of done item 10 for lane actions in this repository.

- Lanes push only `v2.3/<lane-id>/<card-id>-<slug>` and open PRs into `v2.3/next`; they never
  push `dev` or `master`.
- A push to `dev` deploys. Only the integrator moves `dev`, through `~/proj/.wt/bin/promote`,
  after the owner says "deploy dev"; at most once a day. Production only on its own word.
- Never push `master` directly or create `v*` tags.
- Flyway versions come from the integrator's ledger; migrations reach `dev` in ascending order.
- Everything else in the § Forbidden and § owner-word lists of the app repository applies.
