# Backend documentation

## Contracts

- [Teacher reviews](contracts/teacher-reviews.md) — own premoderated reviews and
  Reviews project copies in a teacher's profile, votes, reports, verification,
  the AI summary and the summary levels for tone dots.
- [Subject links and moderation](contracts/subject-links.md) — visibility by schedule
  flow, revisions, premoderation, votes, reports and moderator actions.
- [Privacy and capabilities](contracts/privacy.md)
- [Friendships and public profiles](contracts/friendships.md)
- [Notifications](contracts/notifications.md) — FCM envelope and event payloads.
- [Sport automation](contracts/sport-automation.md) — queues, forecasts, delivery.
- [App version metadata](contracts/app-version.md)
- [Web login and sessions](contracts/web.md) — phone-approved login for the web
  version, the `iw_session` cookie and CSRF rule.
- [Admin API](contracts/admin.md) — web admin routes, role matrix and response shapes.

The wire types are mirrored in `itmo-widgets-core`; its conventions are in
`../../itmo-widgets-core/docs/contract.md`.

## Operations

- [Moderation](ops/moderation.md) — the web admin, role assignment (`ADMIN` by
  SQL), link and review revision decisions, restrictions and policies.
- [Database](ops/database.md) — schema contract, tests, local environment,
  refresh outcomes and retention.
- [Reviews sync](ops/reviews-sync.md) — the daily copy of teacher reviews from
  the Reviews project, its settings, lease, full reload and logs.
- [Service credentials](ops/service-credentials.md) — `service_credentials`
  (My ITMO tokens, the ISU cookie, the Gemini key), seeds, rotation, replacement
  by an admin, the V8 copy and image-only rollback.
- [ISU verification](ops/isu-verification.md) — the ISU session, the check
  whether a teacher taught a review's author, its queue, cache and logs.
- [AI summaries](ops/ai-summaries.md) — summaries of teacher reviews through
  Gemini: input, prompt and answer check, runs and budget, configuration, the
  `gemini-proxy` sidecar, the key and logs.
- [Deployment](ops/deployment.md) — environments, release procedure, backups,
  rollback, the web container and the `/app/` route.
- [Deployment log](ops/deployments.md) — what runs where and since when.

## Rules

Agent rules are in [`AGENTS.md`](../AGENTS.md); history in
[`CHANGELOG.md`](../CHANGELOG.md).
