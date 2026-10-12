# Backend documentation

## Contracts

- [Wire compatibility](contracts/compatibility.md) — what may and may not change
  while installed clients decode responses, the supported client versions, kept
  wire details and status codes. Every contract below follows it.
- [Teacher reviews](contracts/teacher-reviews.md) — own premoderated reviews and
  Reviews project copies in a teacher's profile, votes, reports, verification,
  the AI summary and the summary levels for tone dots.
- [Subject links and moderation](contracts/subject-links.md) — visibility by schedule
  flow, revisions, premoderation, votes, reports and moderator actions.
- [Privacy and capabilities](contracts/privacy.md)
- [Friendships and public profiles](contracts/friendships.md)
- [Notifications](contracts/notifications.md) — FCM envelope, event payloads
  and device registration.
- [Sport automation](contracts/sport-automation.md) — routes, queues, forecasts,
  delivery.
- [Schedule](contracts/schedule.md) — the uploaded schedule snapshot, reads of
  another user's schedule and friends on a lesson.
- [App version metadata](contracts/app-version.md) - latest and minimum
  versions per platform, and the `X-App-Version` header apps send.
- [Web login and sessions](contracts/web.md) — phone-approved login for the web
  version, the `iw_session` cookie and CSRF rule.
- [Admin API](contracts/admin.md) — web admin routes, role matrix and response shapes.

Backend owns the wire types (the `web` package of each feature, see
[Architecture](architecture.md)); the golden fixtures in
`src/test/resources/contract/` pin their JSON, and released Core 1.2.0 and 1.7.0
decode them in the `compatCore120Test` and `compatCore170Test` suites.

[`openapi.json`](openapi.json) is the generated OpenAPI 3.1 catalog of every
route, tagged by feature; `./gradlew test --rerun --tests '*OpenApiSnapshotTest' -Popenapi.record=true`
regenerates it, and the
build fails when it is stale
([wire compatibility](contracts/compatibility.md#generated-openapi-document)).

## Operations

- [Moderation](ops/moderation.md) — the web admin, role assignment (`ADMIN` by
  SQL), link and review revision decisions, restrictions and policies.
- [Database](ops/database.md) — schema contract, migration rules, tests,
  refresh outcomes and retention.
- [Reviews sync](ops/reviews-sync.md) — the daily copy of teacher reviews from
  the Reviews project, its settings, lease, full reload and logs.
- [Service credentials](ops/service-credentials.md) — `service_credentials`
  (My ITMO tokens, the ISU cookie, the Gemini key), seeds, rotation, replacement
  by an admin.
- [ISU verification](ops/isu-verification.md) — the ISU session, the check
  whether a teacher taught a review's author, its queue, cache and logs.
- [AI summaries](ops/ai-summaries.md) — summaries of teacher reviews through
  Gemini: input, prompt and answer check, runs and budget, configuration, the
  `gemini-proxy` sidecar, the key and logs.
- [Account deletion](ops/account-deletion.md) — deleting an account on request
  with [`account-deletion.sql`](ops/account-deletion.sql): confirmation by web
  sign-in, backup, the placeholder author of published links and reviews,
  verification and the answer to the user.
- [Deployment](ops/deployment.md) — the delivery pipeline, environments, where
  to look up what runs where, backups, rollback, the web container and the
  `/app/` route.

## Decisions

- [Decisions](decisions/README.md) - Backend decisions that were argued once:
  migrations, privacy audiences, friendships, people search, the push guard,
  subject link moderation, review verification and AI summaries.

## Code

- [Development](development.md) — local setup, running Backend, build and tests.
- [Architecture](architecture.md) — feature and platform packages, layer rules,
  the boundary test and its allowlists, where a route, DTO, entity, migration
  and test go.

## Rules

How changes are made and released is in
[`CONTRIBUTING.md`](../CONTRIBUTING.md); history in
[`CHANGELOG.md`](../CHANGELOG.md).
