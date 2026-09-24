# Backend documentation

## Contracts

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
  SQL), link revision decisions, restrictions and policies.
- [Database](ops/database.md) — schema contract, tests, local environment,
  refresh outcomes and retention.
- [Deployment](ops/deployment.md) — environments, release procedure, backups,
  rollback, the web container and the `/app/` route.
- [Deployment log](ops/deployments.md) — what runs where and since when.

## Rules

Agent rules are in [`AGENTS.md`](../AGENTS.md); history in
[`CHANGELOG.md`](../CHANGELOG.md).
