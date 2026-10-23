# Client statistics

Which clients signed-in users use: app installs and
[connected clients](connections.md) (agents, MCP servers, the web) with their
builds. Decided in ITMO.Widgets ADR 0034.

## The `X-Client` header

Every client sends `X-Client` on every Backend request: `key=value` pairs
separated by `;`, for example
`kind=android; version=2.3.0; build=20300; channel=play; os=36; install=<uuid>`.

- Keys are `[a-z]+`, values 1-64 characters of `[A-Za-z0-9._/+-]`, the header
  at most 512 characters. Unknown keys and invalid pairs are skipped, the first
  of duplicate keys wins, an invalid `channel` (`[a-z0-9-]{1,16}`) or
  `install` (a UUID) is dropped, and a header without a known `kind` or a
  `version` is ignored. A bad header never fails the request.

| Key | Sent by | Value |
|---|---|---|
| `kind` | everyone | `android`, `ios`, `web`, `agent`, `mcp` |
| `version` | everyone | the version name as built |
| `build` | apps | Android `versionCode`, iOS `CFBundleVersion` |
| `channel` | everyone | `github`, `play`, `appstore`, `testflight`, `sideload`, `dev`, `web` for official builds; any other value (`custom`) for forks and self-built clients |
| `source` | apps, agent, MCP | the building repository `owner/name`, or `local` |
| `commit` | everyone | the build's git commit |
| `base` | custom builds | the upstream version the build is based on |
| `os` | apps, agent, MCP | Android API level, iOS major version, `GOOS-GOARCH` |
| `browser` | web | `<family>/<major>`, e.g. `chrome/131` |
| `install` | apps | a random UUID per install, new after sign-out |

A client is **custom** when its `channel` is not an official one (the build
identity says `custom`) or its `source` is a repository other than
`alllexey-dev/ITMO.Widgets`, `alllexey-dev/itmo-widgets-agent`,
`alllexey-dev/itmo-widgets-web` or `alllexey-dev/itmo-mcp` (case ignored); `source=local` alone does not
make a build custom. A custom client counts under its `base` (its `version` without one).

## What is recorded

`ClientActivityFilter` runs after the security chain on `/api/*`. The
credential picks the instance and, for a connection, the kind:

| Credential | Instance | Recorded from |
|---|---|---|
| App ITMO.ID bearer | `install`, or one legacy instance per user and platform without it | `X-Client` with `kind` `android`/`ios`; without it, `X-App-Version` ([app version](app-version.md#client-version-header)) |
| Connection token or `iw_client` cookie | `connections.id` | `X-Client` with the connection's `kind` (`agent`, `mcp`, `web`); an agent without it, `User-Agent: itmo-widgets-agent/<version>` |

Anonymous requests and a `kind` the credential cannot be are not recorded.
A row is written when the header changed or an hour after its last write, so
`last_seen_at` has hour precision. An instance id another user holds stays
theirs: the write is skipped.

## Storage, retention and privacy

- `client_instances`: one row per instance with `user_id`, the header's fields,
  `custom`, `first_seen_at` and `last_seen_at`, and for an app install
  `itmo_sid`, the `sid` of the install's own ITMO.ID session, written by
  `PUT /api/users/me/id-token` from the ID token for the install its `X-Client`
  names ([devices](connections.md#devices) match it with `id.itmo.ru` sessions
  on the phone). It is personal data: a
  pseudonymous id linked to the account with its build, OS major version and,
  for the web, browser family and major version. Only admins read it. Rows are
  deleted 180 days after `last_seen_at` (nightly, 00:20 Moscow) and with the
  account. No IP, raw user agent, device model or request path is stored.
- `client_daily`: at 00:20 Moscow the job writes the previous day's totals per
  kind, custom flag, group version, channel and source: instances seen in the
  last 1, 7 and 30 days. It holds no user or instance ids and is kept
  indefinitely.

## Admin

`GET /api/admin/system/clients` and the `clients` of `AdminUserDetail`
([admin API](admin.md#clients)).
