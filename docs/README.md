# Backend documentation

## Contracts

- [Privacy and capabilities](contracts/privacy.md)
- [Friendships and public profiles](contracts/friendships.md)
- [Notifications](contracts/notifications.md) — FCM envelope and event payloads.
- [Sport automation](contracts/sport-automation.md) — queues, forecasts, delivery.
- [App version metadata](contracts/app-version.md)

The wire types are mirrored in `itmo-widgets-core`; its conventions are in
`../../itmo-widgets-core/docs/contract.md`.

## Operations

- [Database](ops/database.md) — schema contract, tests, local environment,
  refresh outcomes and retention.
- [Deployment](ops/deployment.md) — environments, release procedure, backups,
  rollback.
- [Deployment log](ops/deployments.md) — what runs where and since when.

## Rules

Agent rules are in [`AGENTS.md`](../AGENTS.md); history in
[`CHANGELOG.md`](../CHANGELOG.md).
