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

- `client_installs`: one row per instance with `user_id`, the header's fields,
  `custom`, `first_seen_at` and `last_seen_at`, and for an app install
  `itmo_sid`, the `sid` of the install's own ITMO.ID session, written by
  `PUT /api/users/me/id-token` from the ID token for the install its `X-Client`
  names ([devices](connections.md#devices) match it with `id.itmo.ru` sessions
  on the phone), and `push_signed_out_at`, when
  `POST /api/users/me/installs/{instanceId}/sign-out` last dropped the
  install's push registration (`InstallInfo.signedOutAt`), cleared by the
  install's next device registration (`POST /api/device/register-device` with
  the same `X-Client` `install`); a header write leaves it alone. It is personal data: a
  pseudonymous id linked to the account with its build, OS major version and,
  for the web, browser family and major version. Only admins read it. Rows are
  deleted 180 days after `last_seen_at` (nightly, 00:20 Moscow) and with the
  account. No IP, raw user agent, device model or request path is stored.
- `stats_clients_daily`: at 00:20 Moscow the job writes the previous day's totals per
  kind, custom flag, group version, channel and source: instances seen in the
  last 1, 7 and 30 days. It holds no user or instance ids and is kept
  indefinitely.

## People

The figures above count clients; `feature/activity` counts people.

- A user's **last activity** is the latest of their clients' `last_seen_at`
  (hour precision), their devices' last registration (`push_devices.last_login`)
  and their connections' last use (`connections.last_used_at`): the
  `lastSeen` of `AdminUserDetail`. A user of Android 2.2 or older, which sends
  neither `X-Client` nor `X-App-Version`, is seen only when its device
  registers. `UserLastSeen` is the one place that defines it; each part comes
  from the service of the feature that records it.
- **Activity weeks**: `ActiveWeekFilter` runs after the security chain on
  `/api/*`, next to `ClientActivityFilter`, and records the Europe/Moscow
  calendar week (Monday to Sunday) of every authenticated request, whatever
  the credential and whether or not the client sends `X-Client`. A request
  the chain rejects (401, 403, 429) is not recorded. The recorder keeps the
  accounts already recorded for the current week in memory and loads them
  from the table when a week starts, so an account reaches the database once
  per week, after a restart too.

### Storage, retention and privacy

- `stats_user_active_weeks`: one row per account and week, `user_id` and
  `week_start` (the Monday), for every week in which the account made an
  authenticated request. It is personal data: in which weeks an account used
  Backend, nothing finer. Rows are kept while the account exists and deleted
  with it (foreign key and [account deletion](../ops/account-deletion.md)).
  No route returns a row of it: admins see cohort counts only.
- `stats_people_daily`: at 00:20 Moscow a job writes the previous day's count of
  distinct people whose last activity is in the last 1, 7 and 30 days, as of
  the run. A rerun the same day keeps the first row. It holds no user ids and
  is kept indefinitely; its history starts the day it shipped.

### Figures

`PeopleStatistics` returns them for the audience tab of the admin statistics
([`GET /api/admin/stats/audience`](admin.md#audience)):

- active people in the rolling 1, 7 and 30 days, by last activity, and the
  accounts counted in the 30 days for a grouping of their own;
- the `stats_people_daily` rows of the last 90 days;
- per registration week (Monday, Moscow) starting in the requested period,
  the latest 26, two cohort views. Unbounded retention: `retained[n-1]` is
  how many accounts of the week have a last activity at least `n` weeks after
  their own registration, for `n` from 1 to 12 the whole cohort has lived
  through. Classic retention: `active[n]` is how many made a request in the
  `n`-th calendar week after the registration week (`active[0]` is that
  week), one element per finished week with no upper limit, null for a week
  before the first week in `stats_user_active_weeks`.
- A cohort is the accounts registered in its week as the users feature counts
  accounts (`UserStatistics`): the placeholder that keeps a deleted account's
  published content is none, so a deleted account leaves its cohort and is
  never active again.

## Admin

`GET /api/admin/system/clients` and the `clients` of `AdminUserDetail`
([admin API](admin.md#clients)) for clients; `GET /api/admin/stats/audience`
([admin API](admin.md#audience)) for people.
