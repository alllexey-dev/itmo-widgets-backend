# Admin API

Wire changes follow the [compatibility rule](compatibility.md).

Routes under `/api/admin/**` serve the web admin. They authenticate like every
other route: an ITMO.ID bearer token or the web session cookie
([web login](web.md)); cookie mutations need `X-Web-Request: 1`. Roles are
checked in the services through `AdminAccess`, so a signed-in user without the
role gets 403 `permission_denied` and anonymous callers get 401 `unauthorized`
before any service runs.

## Roles

| Role | Granted by | May |
|---|---|---|
| `MODERATOR` | an admin, `PUT /api/admin/users/{isu}/roles/MODERATOR` | moderation cases, decisions and restrictions |
| `ADMIN` | SQL only ([moderation ops](../ops/moderation.md)) | everything a moderator may, plus the policy, users and roles, dashboard, sport and system, reviews sync and AI summaries, audit |

| Route | Moderator | Admin |
|---|---|---|
| `GET /api/admin/moderation/cases`, `GET …/cases/{id}`, `POST …/cases/{id}/decisions` | yes | yes |
| `GET /api/admin/moderation/restrictions`, `POST …/restrictions/{id}/revoke` | yes | yes |
| `GET`/`PUT /api/admin/moderation/settings` | no | yes |
| `/api/admin/users/**`, `/api/admin/dashboard`, `/api/admin/system/**`, `/api/admin/reviews/**`, `/api/admin/audit` | no | yes |

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
AdminCaseItem {id: uuid, targetType: "SUBJECT_RESOURCE"|"TEACHER_REVIEW", status, reason, openedAt,
               resolvedAt: instant|null, revision: SubjectLinkRevision|null, link: AdminLinkSummary|null,
               review: AdminReviewSummary|null, author: AdminUserSummary|null, reportCount: long}
AdminLinkSummary {id: uuid, subjectId: long, subjectName: string, periodKey: string, score: int, hidden: boolean}
AdminReviewSummary {id: uuid, teacherIsu: int, subjectTitle: string|null, excerpt: string, score: int,
                    hidden: boolean, anonymous: boolean}
```

A subject link row has `revision` and `link` and a null `review`; a teacher
review row has `review` and null `revision` and `link`. `excerpt` is the first
160 characters of the reviewed revision's text. The target fields and `author`
are null when the target was deleted; `author` is the review's author even for
an anonymous review. `reportCount` counts active (not dismissed) reports.
`SubjectLinkRevision` is defined in [subject links](subject-links.md).

`GET /api/admin/moderation/cases/{id}` and `POST …/cases/{id}/decisions` (body
`ModerationDecisionRequest {action, note?, restriction?: {capability, days?}}`)
return `ModerationCase {id, targetType, status, reason, openedAt, target,
decisions}` with the author's current study groups. The target is
`SubjectLinkTarget` ([subject links](subject-links.md)) or
`TeacherReviewTarget` ([teacher reviews](teacher-reviews.md#dtos)). Only here,
for moderators and admins, `TeacherReviewTarget.review.teacherName` is the
teacher's full name from My ITMO: it is resolved after the transaction, kept
in memory for 12 hours (30 seconds after a failure), never stored, and null when
My ITMO has no name or is unavailable. Decisions keep `moderatorId` as a user
id.

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
{SUBJECT_RESOURCE: ModerationPolicy, TEACHER_REVIEW: ModerationPolicy}}` with
`ModerationPolicy {premoderation, reportThreshold, voteThreshold,
dailySubmissionLimit, dailyReportLimit}`. `PUT` takes the full object with both
types; turning link premoderation off approves the pending link submissions.
Teacher reviews are always premoderated: `TEACHER_REVIEW.premoderation = false`
is 400 `invalid_request_data`.

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

`GET /api/admin/system/app-version?platform=ANDROID|IOS` → `AdminAppVersion
{latest, minimum, note, overridden: boolean, updatedAt: instant|null}` of that
platform. `PUT` with the same parameter takes `{latest, minimum, note?}`:
versions are dotted numbers with an optional `-suffix`, `minimum` must not
exceed `latest`, the note is plain text up to 500 characters. `platform`
defaults to `ANDROID`, so a call without it reads and writes the Android values;
any other value is 400 `invalid_request`. Values are stored in `app_settings`
under the platform's keys and served at once by
[`/api/app/version-info`](app-version.md); an unchanged request writes nothing.

`GET /api/admin/system/credentials` → `AdminServiceCredential[]`, the five rows
of `service_credentials` in this order: `MY_ITMO_REFRESH_TOKEN`,
`MY_ITMO_ACCESS_TOKEN`, `MY_ITMO_ID_TOKEN`, `ISU_KEYCLOAK_IDENTITY`,
`GEMINI_API_KEY` ([service credentials](../ops/service-credentials.md)). There is no value
field: a value never leaves Backend.

```
AdminServiceCredential {key, kind: "REFRESH_TOKEN"|"ACCESS_TOKEN"|"ID_TOKEN"|"COOKIE"|"API_KEY", replaceable: boolean,
                        present: boolean, status: "MISSING"|"UNKNOWN"|"OK"|"EXPIRED"|"FAILED",
                        expiresAt: instant|null, expiresSoon: boolean, lastUsedAt: instant|null,
                        lastRenewedAt: instant|null, lastErrorAt: instant|null, lastError: string|null,
                        updatedAt: instant, updatedSource: "MIGRATION"|"SEED"|"ROTATION"|"ADMIN"|null,
                        updatedByIsu: int|null, updatedByName: string|null}
```

`expiresSoon` is true when the key has a warning window (1 day for
`MY_ITMO_REFRESH_TOKEN`, 14 days for `ISU_KEYCLOAK_IDENTITY`, none for the
others), `expiresAt` is known and less than the window remains. `lastError` is
a short technical line such as `EXPIRED login`, `AUTH sport` or
`AUTH 400 API_KEY_INVALID`.
`updatedByIsu` and `updatedByName` name the admin only for `ADMIN`.

`PUT /api/admin/system/credentials/{key}` with `{value: string}` replaces a
value and returns the list as above. Only `MY_ITMO_REFRESH_TOKEN`,
`ISU_KEYCLOAK_IDENTITY` and `GEMINI_API_KEY` are replaceable. An unknown key is 400
`invalid_request`; a key that is not replaceable, or a value that after trimming
is not 20–8192 printable ASCII characters without `;`, `,`, `"` and `\`, is 400
`invalid_request_data`. The new value becomes `UNKNOWN` with source `ADMIN`
until its first use; replacing the refresh token clears the access and ID
token, a new cookie drops the ISU session and queues every pending review
check at once, and a new Gemini key is used from the next AI summary request. The value is never returned and never reaches a log, the audit
or an error text.

## Reviews

`GET /api/admin/reviews/sync` → `AdminReviewsSync`, the state of the copy of
the Reviews project ([reviews sync](../ops/reviews-sync.md)).
`POST /api/admin/reviews/sync` starts a run in the background and returns the
state with the lease already taken (`running: true`); it is 409
`business_rule_violation` when the sync is disabled or already running.

```
AdminReviewsSync {enabled: boolean, running: boolean, runningSince: instant|null, lastCheckedAt: instant|null,
                  lastChangedAt: instant|null, lastSuccessAt: instant|null,
                  lastOutcome: "UNCHANGED"|"UPDATED"|"FAILED"|null, lastError: string|null,
                  lastAdded: int, lastUpdated: int, lastRemoved: int, upstreamTeachers: int, upstreamReviews: int,
                  reviewsTotal: long, reviewsActive: long, reviewsRemoved: long, teachersActive: long}
```

`enabled` mirrors `REVIEWS_SYNC_ENABLED`. `lastCheckedAt` and `lastOutcome`
describe the latest run, `lastSuccessAt` the latest run that did not fail and
`lastChangedAt` the latest applied snapshot; `lastError` is a short technical
line such as `HTTP 503 /teacher/100123`, null unless the latest run failed.
`lastAdded`, `lastUpdated`, `lastRemoved`, `upstreamTeachers` and
`upstreamReviews` come from the latest applied snapshot. `reviewsTotal`,
`reviewsActive`, `reviewsRemoved` and `teachersActive` (distinct teachers with
an active review) count stored rows. Before the first run the times and
`lastOutcome` are null and the counts are 0.

`GET /api/admin/reviews/verification` → `AdminReviewVerification {pending: long,
verified: long, unverified: long}`, own teacher reviews by the state of their
ISU check ([ISU verification](../ops/isu-verification.md)). The ISU cookie
itself is listed under `/api/admin/system/credentials`.

### AI summaries

The AI summaries of teacher reviews ([AI summaries](../ops/ai-summaries.md)):

| Route | Body | Response data |
|---|---|---|
| `GET /api/admin/reviews/summaries` | — | `AdminAiSummaries` |
| `POST /api/admin/reviews/summaries/run` | — | `AdminAiSummaries` |
| `GET /api/admin/reviews/summaries/teachers?status=&page=&size=` | — | `AdminPage<AdminTeacherSummary>` |
| `PUT /api/admin/reviews/summaries/{isu}/hidden` | `{hidden: boolean}` | `AdminTeacherSummary` |
| `POST /api/admin/reviews/summaries/{isu}/regenerate` | — | `AdminTeacherSummary` |

```
AdminAiSummaries {enabled: boolean, running: boolean, runningSince: instant|null, model: string|null,
                  keyStatus: "MISSING"|"UNKNOWN"|"OK"|"EXPIRED"|"FAILED", lastStartedAt: instant|null,
                  lastFinishedAt: instant|null, lastTrigger: "SCHEDULE"|"ADMIN"|null,
                  lastOutcome: "COMPLETED"|"BUDGET_EXHAUSTED"|"RATE_LIMITED"|"NO_KEY"|"AUTH_FAILED"|"FAILED"|null,
                  lastError: string|null, lastGenerated: int, lastFailed: int, lastRequests: int,
                  ready: long, pending: long, failed: long, hidden: long,
                  budgetDay: "YYYY-MM-DD", budgetUsed: int, dailyBudget: int}
AdminTeacherSummary {teacherIsu: int, teacherName: string|null, status: "READY"|"PENDING"|"FAILED"|"HIDDEN",
                     inputCount: int, reviewCount: int|null, summary: TeacherSummary|null, hidden: boolean,
                     hiddenAt: instant|null, hiddenByName: string|null, attempts: int,
                     lastAttemptAt: instant|null, lastError: string|null}
```

- `enabled` mirrors `AI_SUMMARY_ENABLED`, `model` is the configured model or
  null, `keyStatus` the status of the `GEMINI_API_KEY` row. `lastError` is a
  short technical line such as `RATE_LIMITED 429` or `AUTH 400 API_KEY_INVALID`,
  null after a clean run. `lastGenerated`, `lastFailed` and `lastRequests` count
  built summaries, rejected answers and Gemini requests of the latest run.
- `ready`, `pending`, `failed` and `hidden` count teachers by status:
  `HIDDEN` — an admin hid the summary; `READY` — the summary matches the current
  reviews; `FAILED` — it does not, and a model answer was rejected since the
  reviews changed; `PENDING` — it does not and waits for a request. Teachers with
  fewer than three reviews that are not hidden have no status.
- `budgetDay` is today in America/Los_Angeles, `budgetUsed` the Gemini requests
  spent on it (0 when the stored day is another one) and `dailyBudget` the
  configured `AI_SUMMARY_DAILY_BUDGET`, shared by the night run and the admin.
- `run` starts a run in the background («Пересчитать всё», which also gives
  failed teachers fresh attempts) and returns the state with the lease already
  taken; it is 409 `business_rule_violation` when summaries are disabled or
  already running.
- `teachers` lists teachers with a status, most reviews first; `status` filters
  by one status (an unknown name is 400 `invalid_request`). `teacherName` comes
  from an active Reviews copy, otherwise from My ITMO (not stored, null when
  unknown). `inputCount` is the current number of reviews, `reviewCount` the
  number the stored summary was built from, and `summary` its content even when
  hidden (null without content). `lastError` is the latest rejection code such
  as `SCHEMA scales`.
- `hidden` hides or shows the summary for users; an unchanged request writes
  nothing. A missing or non-boolean `hidden`, `"true"` included, is 400
  `invalid_request`.
- `regenerate` puts the teacher first in the queue with fresh attempts and
  starts a run unless one is going, which then takes the teacher next. It is
  409 `business_rule_violation` for a teacher with fewer than three reviews, a
  hidden summary or disabled summaries.
- Both mutations are 404 `not_found` for a teacher without a summary row and
  return the updated row.

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
| `APP_VERSION_CHANGED` | `app-version` | `latest 2.1 -> 2.3; minimum …; note changed`; iOS changes start with `IOS: ` |
| `REVIEWS_SYNC_STARTED` | `reviews-sync` | none |
| `SERVICE_CREDENTIAL_REPLACED` | `credential:<key>` | none |
| `AI_SUMMARIES_RUN_STARTED` | `ai-summaries` | none |
| `AI_SUMMARY_HIDDEN`, `AI_SUMMARY_SHOWN` | `teacher:<isu>` | none |
| `AI_SUMMARY_REGENERATION_REQUESTED` | `teacher:<isu>` | none |

Entries are written in the same transaction as the change and only when
something changed; `REVIEWS_SYNC_STARTED` is written when an admin's start takes
the sync lease, so a start answered 409 leaves no entry; `AI_SUMMARIES_RUN_STARTED`
likewise only when an admin's «Пересчитать всё» takes the summaries lease, and
never for the night run.
`SERVICE_CREDENTIAL_REPLACED` is written for every accepted replacement in the
transaction that stores the value, and never contains it. Moderation decisions
stay in `moderation_decisions`, not in this audit.
