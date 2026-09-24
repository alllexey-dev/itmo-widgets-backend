# Admin API

Routes under `/api/admin/**` serve the web admin. They authenticate like every
other route: an ITMO.ID bearer token or the web session cookie
([web login](web.md)); cookie mutations need `X-Web-Request: 1`. Roles are
checked in the services through `AdminAccess`, so a signed-in user without the
role gets 403 `permission_denied` and anonymous callers get 403 before any
service runs.

## Roles

| Role | Granted by | May |
|---|---|---|
| `MODERATOR` | an admin, `PUT /api/admin/users/{isu}/roles/MODERATOR` | moderation cases, decisions and restrictions |
| `ADMIN` | SQL only ([moderation ops](../ops/moderation.md)) | everything a moderator may, plus the policy, users and roles, dashboard, sport and system, audit |

| Route | Moderator | Admin |
|---|---|---|
| `GET /api/admin/moderation/cases`, `GET …/cases/{id}`, `POST …/cases/{id}/decisions` | yes | yes |
| `GET /api/admin/moderation/restrictions`, `POST …/restrictions/{id}/revoke` | yes | yes |
| `GET`/`PUT /api/admin/moderation/settings` | no | yes |
| `/api/admin/users/**`, `/api/admin/dashboard`, `/api/admin/system/**`, `/api/admin/audit` | no | yes |

The legacy `PUT /api/moderation/settings` is admin-only as well and audited the
same way; `GET /api/moderation/settings` stays readable by moderators.

## Conventions

Every response is the usual `ApiResponse` envelope; the shapes below are its
`data`. Keys are camelCase, times are ISO-8601 UTC instants, dates are
`YYYY-MM-DD`, enums are name strings, and nullable keys are always present.

Lists are pages: `?page=` (zero-based, default 0) and `?size=` (1–100, default
20); anything else is 400 `invalid_request_data`.

```
AdminPage<T>      {items: T[], page: int, size: int, total: long}
AdminUserSummary  {isu: int, name: string, pictureUrl: string|null, groups: GroupData[]}
GroupData         {name: string, course: int, facultyShortName: string}
```

`AdminUserSummary.groups` in lists are the groups stored from the ID token,
highest course first (no MyITMO call per row); single-user views resolve the
current groups.

## Moderation

`GET /api/admin/moderation/cases?status=OPEN&reason=&page=&size=` →
`AdminPage<AdminCaseItem>`. `status` is `OPEN` (default), `RESOLVED` or
`WITHDRAWN`; `reason` is optional `SUBMISSION`, `REPORTS` or `VOTES`. Open cases
come oldest first (queue order), closed ones most recently resolved first. A
page takes a fixed number of queries whatever the number of authors.

```
AdminCaseItem {id: uuid, targetType: "SUBJECT_RESOURCE", status, reason, openedAt, resolvedAt: instant|null,
               revision: SubjectLinkRevision|null, link: AdminLinkSummary|null, author: AdminUserSummary|null,
               reportCount: long}
AdminLinkSummary {id: uuid, subjectId: long, subjectName: string, periodKey: string, score: int, hidden: boolean}
```

`revision`, `link` and `author` are null when the target was deleted;
`reportCount` counts active (not dismissed) reports. `SubjectLinkRevision` is
defined in [subject links](subject-links.md).

`GET /api/admin/moderation/cases/{id}` and `POST …/cases/{id}/decisions` (body
`ModerationDecisionRequest {action, note?, restriction?: {capability, days?}}`)
return `ModerationCase {id, targetType, status, reason, openedAt, target,
decisions}` as in [subject links](subject-links.md), with the author's current
study groups. Decisions keep `moderatorId` as a user id.

`GET /api/admin/moderation/restrictions?isu=&active=true&page=&size=` →
`AdminPage<AdminRestriction>`, newest first. Without `isu` it lists every user;
`active=false` includes expired and revoked restrictions.

```
AdminRestriction {id: uuid, user: AdminUserSummary, capability, reason: string, startsAt: instant,
                  expiresAt: instant|null, revokedAt: instant|null, revokedByIsu: int|null, active: boolean,
                  caseId: uuid}
```

`POST /api/admin/moderation/restrictions/{id}/revoke` → `null`; revoking twice
is a no-op.

`GET`/`PUT /api/admin/moderation/settings` → `ModerationSettings {policies:
{SUBJECT_RESOURCE: {premoderation, reportThreshold, voteThreshold,
dailySubmissionLimit, dailyReportLimit}}}`. `PUT` takes the full object; turning
premoderation off approves the pending submission queue.

## Users

`GET /api/admin/users?query=&page=&size=` → `AdminPage<AdminUserItem>`, newest
registrations first. `query` (at most 100 characters) matches an ISU prefix or a
part of the name or of a stored group name, ignoring case; `%` and `_` are
literal. No query lists everybody.

```
AdminUserItem {isu: int, name: string, pictureUrl: string|null, groups: GroupData[], roles: string[], createdAt: instant}
```

`GET /api/admin/users/{isu}` → `AdminUserDetail` (404 `not_found` for an unknown
ISU):

```
AdminUserDetail {user: AdminUserSummary (current groups), roles: string[], groups: GroupData[] (every stored group),
                 createdAt: instant, devices: {name: string, lastLogin: instant}[], friendsCount: long,
                 linksCount: long, restrictions: AdminRestriction[] (last 50, all states), lastSeen: instant|null}
```

`lastSeen` is the latest device login or web session use. Devices never expose
FCM tokens.

`PUT /api/admin/users/{isu}/roles/MODERATOR` and `DELETE …/roles/MODERATOR` →
`string[]`, the user's roles afterwards. Both are idempotent; any other role
name, including `ADMIN`, is 400 `invalid_request_data`.

## Dashboard

`GET /api/admin/dashboard` → `AdminDashboard {totals, days}`:

```
AdminDashboardTotals {users, newUsers7d, activeDevices7d, activeDevices30d, webSessions7d, friendships,
                      links: {PRIVATE, PENDING, PUBLISHED, REJECTED, HIDDEN}, openCases,
                      activeAutoSignEntries, activeFreeSignEntries}   (all long)
AdminDashboardDay {date: "YYYY-MM-DD", newUsers: long, activeDevices: long, createdLinks: long}
```

Windows are rolling 7 or 30 days from now. Devices are active by
`devices.last_login`; web sessions count sessions created; friendships are
accepted ones; `links` uses the owner-side link status; sport entries are
waiting or notified and not cancelled. `days` has exactly 30 zero-filled
Europe/Moscow days, oldest first, ending today; a device counts on the day of
its latest login only, because earlier logins are not stored.

## System

`GET /api/admin/system/sport` → `AdminSportStatus`:

```
AdminSportStatus {runs: AdminSportRun[] (last 50, newest first), outcomes7d: {SUCCESS, PARTIAL, FAILED},
                  errors7d: {AUTH, NETWORK, HTTP, MAPPING, PERSISTENCE, INTERNAL}, averageDurationMillis7d: long|null,
                  lastSuccessAt: instant|null, activeAutoSignEntries: long, activeFreeSignEntries: long}
AdminSportRun {id: long, timestamp: instant, outcome, durationMillis: long, receivedLessons: int,
               newLessonsAdded: int, updatedLessons: int, skippedLessons: int, errorCategory: string|null}
```

`GET /api/admin/system/app-version` → `AdminAppVersion {latest, minimum, note,
overridden: boolean, updatedAt: instant|null}`. `PUT` takes
`{latest, minimum, note?}`: versions are dotted numbers with an optional
`-suffix`, `minimum` must not exceed `latest`, the note is plain text up to 500
characters. Values are stored in `app_settings` and served at once by
[`/api/app/version-info`](app-version.md); an unchanged request writes nothing.

## Audit

`GET /api/admin/audit?page=&size=` → `AdminPage<AdminAuditEntry>`, newest first:

```
AdminAuditEntry {id: uuid, action: string, target: string, details: string|null, createdAt: instant,
                 actorIsu: int, actorName: string}
```

| Action | Target | Details |
|---|---|---|
| `ROLE_GRANTED`, `ROLE_REVOKED` | `user:<isu>` | `role MODERATOR` |
| `MODERATION_SETTINGS_CHANGED` | `moderation-settings` | `SUBJECT_RESOURCE.premoderation true -> false; …` |
| `APP_VERSION_CHANGED` | `app-version` | `latest 2.1 -> 2.3; minimum …; note changed` |

Entries are written in the same transaction as the change and only when
something changed. Moderation decisions stay in `moderation_decisions`, not in
this audit.
