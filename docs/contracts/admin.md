# Admin API

Wire changes follow the [compatibility rule](compatibility.md).

Routes under `/api/admin/**` serve the web admin. They authenticate like every
other route: an ITMO.ID bearer token or the web's `iw_client` cookie
([connected clients](connections.md)); cookie mutations need
`X-Web-Request: 1`. A web connection whose last phone approval is more than 7
days old (`approvedAt`, `WEB_ADMIN_MAX_AGE`) gets 401 `reauth_required` on these
routes and on `/api/moderation/**`
([fresh approval](connections.md#fresh-approval-for-admin-routes)); a bearer
token has no such limit. Agent and MCP connections never reach them. Roles are checked in the services through `AdminAccess`, so a
signed-in user without the role gets 403 `permission_denied` and anonymous
callers get 401 `unauthorized` before any service runs.

## Roles

| Role | Granted by | May |
|---|---|---|
| `MODERATOR` | an admin, `PUT /api/admin/users/{isu}/roles/MODERATOR` | moderation cases, decisions and restrictions |
| `ADMIN` | SQL only ([moderation ops](../ops/moderation.md)) | everything a moderator may, plus the policy, users and roles, dashboard, statistics, sport and system, reviews sync and AI summaries, audit |

| Route | Moderator | Admin |
|---|---|---|
| `GET /api/admin/moderation/cases`, `GET …/cases/{id}`, `POST …/cases/{id}/decisions` | yes | yes |
| `GET /api/admin/moderation/restrictions`, `POST …/restrictions/{id}/revoke` | yes | yes |
| `GET`/`PUT /api/admin/moderation/settings` | no | yes |
| `/api/admin/users/**`, `/api/admin/dashboard`, `/api/admin/system/**`, `/api/admin/reviews/**`, `/api/admin/audit` | no | yes |
| `/api/admin/stats/**` | no | yes |

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

`AdminUserSummary.groups` are the groups stored from the ID token, highest
course first.

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
decisions}`. The target is
`SubjectLinkTarget` ([subject links](subject-links.md)) or
`TeacherReviewTarget` ([teacher reviews](teacher-reviews.md#dtos)). Only here,
for moderators and admins, `TeacherReviewTarget.review.teacherName` is the
teacher's full name from My ITMO: it is resolved after the transaction, kept
in memory for 12 hours (30 seconds after a failure), never stored, and null when
My ITMO has no name or is unavailable. Decisions keep `moderatorId` as a user
id.

A moderator never decides a case about their own content: any decision on a
case whose target belongs to the caller is 403 `permission_denied`. An admin
may (self-moderation), except `RESTRICT_USER` against themselves, which is 403
too. `RESTRICT_USER` against a moderator or an admin is 403 unless the caller
is an admin.

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
types; turning link premoderation off approves the pending link submissions,
except the caller's own, which wait for another moderator.
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
AdminUserDetail {user: AdminUserSummary, roles: string[], groups: GroupData[] (every stored group),
                 createdAt: instant, devices: AdminDevice[] (latest login first), friendsCount: long,
                 linksCount: long, restrictions: AdminRestriction[] (last 50, all states), lastSeen: instant|null,
                 agentAccess: boolean, clients: AdminClient[] (latest first)}
AdminClient {kind: "ANDROID"|"IOS"|"WEB"|"AGENT", name: string|null (agent token name), version: string,
             build: string|null, channel: string|null, source: string|null, commit: string|null, base: string|null,
             custom: boolean, os: string|null, browser: string|null, firstSeenAt: instant, lastSeenAt: instant}
AdminDevice {name: string, lastLogin: instant, appVersion: string|null, appBuild: int|null,
             appPlatform: "ANDROID"|"IOS"|null, appDistribution: string|null, appVersionSeenAt: instant|null,
             platform: "ANDROID"|"IOS"}
```

`lastSeen` is the latest device login, connection use (recorded at most every
5 minutes) or client request (recorded at most hourly). `clients` are the
user's app installs and connections from [client statistics](clients.md);
`name` is a connection's name, null for an app install. Devices never expose FCM tokens. The `app*` fields
are the build the device last reported
([client version header](app-version.md#client-version-header)) and when; all
are null for a device that never reported one (Android 2.2 and older);
`appVersion` alone may come from the registration body. `platform` is the one
the device registered with (`ANDROID` for every device of a released Android
client), or `IOS` for an iOS device Backend created from its reports
([devices without push](app-version.md#devices-without-push)); such a device is
named `iOS, no push token` until a registration names it.

`agentAccess` says whether an admin let the user into self-hosted agents.
`PUT /api/admin/users/{isu}/agent-access` with `{enabled: boolean}` →
`AdminAgentAccess {enabled}` turns that on or off
([connected clients](connections.md#clients)). It is idempotent, audits
`AGENT_ACCESS_GRANTED` or `AGENT_ACCESS_REVOKED` once per change and keeps the
user's agent connections either way.

`PUT /api/admin/users/{isu}/roles/MODERATOR` and `DELETE …/roles/MODERATOR` →
`string[]`, the user's roles afterwards. Both are idempotent; any other role
name, including `ADMIN`, is 400 `invalid_request_data`.

## Dashboard

`GET /api/admin/dashboard` → `AdminDashboard {totals, days}`:

```
AdminDashboardTotals {users, newUsers7d, activeDevices7d, activeDevices30d, devicesByPlatform: {ANDROID, IOS},
                      webSessions7d, friendships, links: {PRIVATE, PENDING, PUBLISHED, REJECTED, HIDDEN}, openCases,
                      activeAutoSignEntries, activeFreeSignEntries}   (all long)
AdminDashboardDay {date: "YYYY-MM-DD", newUsers: long, activeDevices: long, createdLinks: long}
```

Windows are rolling 7 or 30 days from now. `users`, `newUsers7d` and
`days[].newUsers` count accounts as [statistics](#statistics) do: the
placeholder a deleted account leaves behind is not one. Devices are active by
`devices.last_login`; `devicesByPlatform` counts every registered device by
its registered platform; `webSessions7d` counts web connections created (the
name predates them); friendships are
accepted ones; `links` uses the owner-side link status; sport entries are
waiting or notified and not cancelled. `days` has exactly 30 zero-filled
Europe/Moscow days, oldest first, ending today; a device counts on the day of
its latest login only, because earlier logins are not stored.

## Statistics

Figures of the statistics page, one route per tab under `/api/admin/stats/`.

### Conventions

- **Who**: `ADMIN` only. A moderator gets 403 `permission_denied`, an
  anonymous caller 401 `unauthorized`.
- **Period**: `?from=YYYY-MM-DD&to=YYYY-MM-DD`, Europe/Moscow days, both
  inclusive. Defaults: `to` is today, `from` is `to` minus 29 days. `from`
  after `to`, `to` in the future or more than 1100 days is 400
  `invalid_request_data`; an unreadable date is 400 `invalid_request`. The
  answer repeats `from` and `to`.
- **Daily series**: `days` has exactly one row per day of the period,
  zero-filled, oldest first.
- **Counts only**: no user id, ISU, name, instance id or IP. Sport section
  names are catalog data and are the one text in the answers.
- **Enums**: these shapes reach only the web, which shows a name it does not
  know as sent, so a later value is not a breaking change; no shape here is
  read by Core.
- **Deleted rows are not counted**: a deleted account, link or review takes
  its rows with it. Each route names what else its tables forget.
- **Cost of a call**: a fixed number of aggregate queries, no rows loaded, one
  read-only transaction. The tables have no index on `created_at` alone, so a
  call scans them; an index is added when a scan gets slow.

### Sport

`GET /api/admin/stats/sport?from=&to=` -> `AdminSportStats`:

```
AdminSportStats {from: date, to: date, days: AdminSportStatsDay[], autoSign: AdminSportEntryStats,
                 freeSign: AdminSportEntryStats, subscriptions: AdminSportSubscriptionStats,
                 sections: AdminSportSectionStats[], slots: AdminSportSlotStats[]}
AdminSportStatsDay {date, autoSign: AdminSportDayEntries, freeSign: AdminSportDayEntries, signUps: long}
AdminSportDayEntries {created: long, satisfied: long}
AdminSportEntryStats {created: long,
                      outcomes: {WAITING, NOTIFIED, SATISFIED, EXPIRED, GAVE_UP_NOTIFYING, CANCELLED} (all long),
                      timed: long, medianSeconds: long|null, p90Seconds: long|null}
AdminSportSubscriptionStats {active: long, stopped: {USER, EXPIRED} (long), created: long,
                             occurrences: {QUEUED, LIMIT, SKIPPED} (long)}
AdminSportSectionStats {sectionName: string, autoSignEntries: long, freeSignEntries: long, satisfied: long,
                        medianWaitSeconds: long|null}
AdminSportSlotStats {weekday: int (1 Monday - 7 Sunday), timeStart: "HH:mm", entries: long, satisfied: long}
```

- An entry belongs to the period by its `created_at`. Its outcome is
  `CANCELLED` when it was cancelled, otherwise its status; `outcomes` has
  every key.
- `days[].autoSign` and `freeSign`: entries created on the day and how many of
  them ended `SATISFIED` (the latest days still have waiting entries).
  `days[].signUps`: entries of both kinds whose `satisfied_at` is on the day,
  whenever they were created and even if cancelled later; its sum over 7 days
  is `Записи на спорт` on the home page.
- `autoSign.medianSeconds` and `p90Seconds`: `satisfied_at - first_notified_at`
  over the `timed` satisfied entries that have both times (how fast a place is
  taken once the sign-up opens). `freeSign`: `satisfied_at - created_at` (how
  long a queue is waited in). Both are `percentile_cont` rounded to whole
  seconds, null when `timed` is 0.
- `subscriptions.active` and `stopped` are all subscriptions as they are now;
  `created` and `occurrences` belong to the period (the subscription's
  `created_at`, the occurrence's start).
- `sections`: at most 50, by entries of both kinds descending, then by name.
  An auto entry counts under the section it targets, a queue entry under its
  lesson's section (trimmed). `medianWaitSeconds` is over the satisfied
  entries of both kinds, `satisfied_at - created_at`, null without one.
- `slots`: auto entries by the weekday and start minute of the lesson they
  target in Europe/Moscow, only slots with an entry, by weekday and time.

Entries are never cleaned up, so the history is whole; a deleted account
takes its entries and subscriptions with it.

### Reviews and moderation

`GET /api/admin/stats/moderation?from=&to=` -> `AdminModerationStats`:

```
AdminModerationStats {from: date, to: date, days: AdminModerationStatsDay[], ownReviews: long,
                      cases: AdminCaseStats[], decisions: AdminDecisionStats[], reports: AdminReportStats[]}
AdminModerationStatsDay {date, newReviews: long, reviewVotes: long, externalReviewVotes: long, linkVotes: long,
                         casesOpened: long, casesResolved: long, casesWithdrawn: long, backlog: long,
                         reports: long}
AdminCaseStats {targetType: "SUBJECT_RESOURCE"|"TEACHER_REVIEW", reason: "SUBMISSION"|"REPORTS"|"VOTES",
                opened: long, resolved: long, withdrawn: long,
                medianSecondsToDecision: long|null, maxSecondsToDecision: long|null}
AdminDecisionStats {targetType, action: "APPROVE"|"REJECT"|"HIDE"|"RESTORE"|"DISMISS"|"RESTRICT_USER"|"HIDE_ALL_BY_USER",
                    actor: "MODERATOR"|"POLICY", count: long}
AdminReportStats {targetType, reason: "BROKEN"|"WRONG_SUBJECT"|"SPAM"|"OTHER"|"OFFENSIVE"|"WRONG_TEACHER",
                  total: long, dismissed: long}
```

- `ownReviews` is every own teacher review now, hidden ones included;
  `days[].newReviews` counts own reviews by `teacher_reviews.created_at`.
- `days[].reviewVotes` (own reviews), `externalReviewVotes` (Reviews copies)
  and `linkVotes` count vote rows by their `created_at`. A row is one per
  voter and target: changing the value keeps its day, taking the vote back
  deletes the row. So a series is the votes that still stand, by the day they
  were first cast; a vote taken back is gone from its day.
- `days[].casesOpened` by `opened_at`; `casesResolved` and `casesWithdrawn` by
  `resolved_at` and the status, whenever the case was opened; `backlog` is the
  cases open at the end of the day (opened by then and not closed by then).
  The whole series is one query. Cases and decisions are never deleted, so
  these are exact for any period.
- `cases`: per target type and reason, the cases opened in the period and how
  they ended so far; `medianSecondsToDecision` and `maxSecondsToDecision` are
  over the `resolved` ones, `resolved_at - opened_at` in whole seconds, null
  without one.
- `decisions`: decisions made in the period by the target type of their case,
  action and actor: `MODERATOR`, or `POLICY` for the automatic approvals of the
  policy and the ISU check.
- `reports`: reports created in the period by target type and reason, and how
  many of them are dismissed now; `days[].reports` counts them per day. A
  report is deleted with its target, so a removed link or review takes its
  reports out of every series.
- The lists hold only the combinations that occur, sorted by their enum names.

A deleted account takes its votes and its unpublished reviews with it; its
published reviews and its reports stay under the placeholder that replaces it.
Five aggregate queries over the moderation tables, four over the reviews and
one over the link votes.

### Features

`GET /api/admin/stats/features?from=&to=` -> `AdminFeatureStats`:

```
AdminFeatureStats {from: date, to: date, users: long, usersWithFriends: long, socialUsers: long,
                   features: AdminFeatureUsage[], days: AdminFeatureStatsDay[], privacy: AdminPrivacyStats,
                   pairing: AdminPairingFunnel[]}
AdminFeatureUsage {feature: string, events: long|null, users: long}
AdminFeatureStatsDay {date, friendRequests: long, friendAccepts: long,
                      connectionsCreated: {agent, mcp, web} (long), connectionsRevoked: {agent, mcp, web} (long)}
AdminPrivacyStats {schedule: {ALL, FRIENDS, NOBODY} (long), sport: {ALL, FRIENDS, NOBODY} (long),
                   friends: {ALL, FRIENDS, NOBODY} (long)}
AdminPairingFunnel {client: "agent"|"mcp"|"web", created: long, opened: long, approved: long, claimed: long,
                    expired: long}
```

`features` has one row per feature, sorted by name, each counted over the
period:

| `feature` | `events` | `users` (distinct) |
|---|---|---|
| `CONNECTIONS` | connections of every client created | their users |
| `FRIENDS` | friend requests sent (friendship rows created) | the requester and the addressee of every row sent or accepted |
| `LINKS` | subject links created | owners |
| `LINK_VOTES` | votes on subject links first cast | voters |
| `REPORTS` | reports created | reporters |
| `REVIEWS` | own teacher reviews created | authors |
| `REVIEW_VOTES` | votes on own reviews and Reviews copies first cast | voters |
| `SCHEDULE` | null | users with an uploaded lesson dated in the period |
| `SPORT_AUTO_SIGN` | auto-sign entries created | their users |
| `SPORT_FREE_SIGN` | queue entries created | their users |
| `SPORT_SECTIONS` | section subscriptions created | their users |

- Each row belongs to the feature it counts, and a new feature adds a row; the
  web shows a name it does not know as sent. Votes and reports keep the rules
  of [reviews and moderation](#reviews-and-moderation): a vote taken back, a
  report of a removed target are gone.
- `socialUsers`: the distinct users over every row of their own actions, that
  is every row but `SCHEDULE`, which the app uploads on its own.
- `users` is every account now, `usersWithFriends` the accounts with an
  accepted friendship now (`Доля пользователей с друзьями` is their ratio). The
  placeholder that keeps a deleted account's published links, reviews and
  reports is not an account, but counts in the rows of what it keeps, one per
  deleted account.
- `days[].friendRequests` by `friendships.created_at`, `friendAccepts` by
  `responded_at`. Rejecting, cancelling and removing a friend delete the row,
  and so does deleting either account: the series are the requests and
  friendships that still stand, not everything that was sent.
- `days[].connectionsCreated` and `connectionsRevoked` by `connections.created_at`
  and `revoked_at`, keyed by the client names of
  [connections](connections.md) and with every client. A revoked connection is
  deleted 90 days after its revocation (and with its account), so both series
  undercount days older than 90 days.
- `privacy`: accounts per value of `schedule_visibility`, `sport_visibility`
  and `friends_visibility`, as they are now.
- `pairing`: the `CONNECT` pairings of each client over the period, one row
  per client in the order above, from the `pairing` counters of
  [server statistics](server-statistics.md): codes created, opened, approved
  and claimed, and pending codes that expired. Each step counts once per
  pairing on the Moscow day it happened, so a pairing that spans midnight
  counts its steps on two days, and a period's steps need not add up to a
  funnel of its own codes. Days before the counters started
  ([`collectedSince`](#server)) are zeros.

One aggregate query per row of `features`, two over friendships for the
daily series and one for `usersWithFriends`, one over connections, two over
the accounts, one over the pairing counters.

### Audience

`GET /api/admin/stats/audience?from=&to=` -> `AdminAudienceStats`:

```
AdminAudienceStats {from: date, to: date, usersBefore: long, days: AdminAudienceDay[],
                    activePeople: AdminActivePeople, peopleHistory: AdminPeopleDay[],
                    retention: AdminRetentionCohort[], retentionClassic: AdminClassicCohort[],
                    faculties: AdminFacultyCount[], courses: AdminCourseCount[]}
AdminAudienceDay {date, newUsers: long}
AdminActivePeople {active1d: long, active7d: long, active30d: long}
AdminPeopleDay {day: date, active1d: long, active7d: long, active30d: long}
AdminRetentionCohort {weekStart: date, size: long, retained: long[]}
AdminClassicCohort {weekStart: date, size: long, active: (long|null)[]}
AdminFacultyCount {facultyShortName: string|null, facultyName: string|null, users: long, active30d: long}
AdminCourseCount {course: int|null, users: long, active30d: long}
```

- **Accounts**: the placeholder that keeps a deleted account's published
  content is not an account anywhere here: not in the growth, a cohort, a
  faculty or a course. A deleted account leaves every figure.
- `days[].newUsers` by `users.created_at`; `usersBefore` is the accounts
  created before `from`, so the running total is `usersBefore` plus the sum.
  With the 1100-day limit the web draws the whole history and folds days into
  months itself.
- A person's **last activity** is the latest of their clients' last request,
  device registration and connection use: the `lastSeen` of
  `AdminUserDetail` ([client statistics](clients.md#people)). It has hour
  precision, and a user of Android 2.2 and older is seen only when the device
  registers.
- `activePeople`: distinct people whose last activity is in the rolling 1, 7
  and 30 days, now. `peopleHistory`: the nightly counts of the last 90 days,
  oldest first, whatever the period; `day` is the day the count closes. The
  history starts the day the nightly job shipped, with no backfill. These are
  the headline active figures; [clients](#clients) stays the breakdown by kind
  and version.
- `retention` and `retentionClassic`: one row each per registration week
  (Monday, Moscow) whose Monday is a day of the period, the latest 26, oldest
  first; `size` is the accounts registered in the week.
  - `retention[].retained[n-1]` is how many of them have a last activity at
    least `n` weeks after their own registration, for `n` from 1 to 12, only
    for the `n` the whole cohort has lived through: a week whose youngest
    account could be younger than `n` weeks has fewer elements, a current one
    none. It is unbounded: a person back in week 5 counts in weeks 1 to 5.
  - `retentionClassic[].active[n]` is how many of them made a request in the
    `n`-th calendar week after the registration week (`active[0]` is that
    week), one element per finished week with no upper limit. A week before
    the activity weeks were first collected is null, never 0.
- `faculties` and `courses`: every account by the faculty and the course of
  its current group (the first of `AdminUserSummary.groups`: the highest
  course, then the group name), and `active30d` those of them with a last
  activity in 30 days. An account without a stored group is the row with
  nulls. `faculties` go by `users` descending, then by short name; `courses`
  by course; the null row of each is last.

| Web metric | Fields |
|---|---|
| Рост числа пользователей за всё время | `usersBefore`, `days[].newUsers` |
| Активные пользователи (люди, а не установки) | `activePeople`, `peopleHistory` |
| Удержание: кто остался через N недель | `retention`, `retentionClassic` |
| Пользователи по факультетам и курсам | `faculties`, `courses` |

Two queries over the accounts for the growth, one for the groups; the three
queries of the last activity, read once per call; one over the registrations
of the cohort weeks, one over `user_active_weeks` and one for its first week;
one over `people_daily`. Unlike the other tabs, the last activity and the
cohorts are read into memory as account ids and times, nothing else, and
counted there; the accounts active in 30 days go back to the groups query as
a parameter. No id leaves Backend.

### Server

`GET /api/admin/stats/server?from=&to=` -> `AdminServerStats`, from the daily
counters of [server statistics](server-statistics.md):

```
AdminServerStats {from: date, to: date, collectedSince: date|null, days: AdminServerDay[],
                  routes: AdminRouteStats[], myItmo: AdminUpstreamStats[], push: AdminPushStats[]}
AdminServerDay {date, requests: long, errors5xx: long, p50Millis: int|null, p95Millis: int|null,
                myItmoCalls: long, myItmoFailures: long, pushSent: long, pushFailed: long, pushTokenGone: long}
AdminRouteStats {group: string, requests: long, errors4xx: long, errors5xx: long, p50Millis: int|null,
                 p95Millis: int|null}
AdminUpstreamStats {operation: string, calls: long, failures: {AUTH, HTTP, NETWORK, MAPPING} (long)}
AdminPushStats {type: string, sent: long, failed: long, tokenGone: long, noDevice: long}
```

- `collectedSince` is the first day with any counter, null before the first
  one is written. Days before it are zeros and nulls: the web says the
  counters had not started instead of drawing a flat line. Counts of the
  current minute appear after the next flush.
- `days[].requests` and `errors5xx`: `/api/` answers of the day and the `5xx`
  among them. `p50Millis` and `p95Millis`: the median and p95 response time
  of the day's timed answers, null on a day without one (the agent long-poll
  counts as a request but is never timed). Read from latency buckets, so a
  value is as precise as its bucket is wide, and a rank over 5 s reads as
  5000.
- `routes`: per route group over the period (`sport`, `friends`, ...,
  `other` for no matching route), with the 4xx and 5xx answers and the times;
  by `requests` descending, then by group.
- `days[].myItmoCalls` counts every call of the technical My ITMO credential
  and `myItmoFailures` those that failed. `myItmo`: per gateway operation
  (`sportSchedule`, `personality`, ...) over the period, `failures` by kind
  with every key; by `calls` descending, then by operation.
- `days[].pushSent`, `pushFailed` and `pushTokenGone` count devices: `sent`
  means FCM accepted the message (FCM reports no delivery, so the web label
  says "sent", not "delivered"), `tokenGone` that FCM reported the
  registration gone. `push`: per FCM `type` over the period, with `noDevice`
  the users who had no device; by its total descending, then by type.
- `group`, `operation` and `type` are labels as they were counted: a later
  build may add or retire one, and the web shows a name it does not know as
  sent.

| Web metric | Fields |
|---|---|
| Доставка уведомлений | `push`, `days[].push*` |
| Сбои My ITMO | `myItmo`, `days[].myItmo*` |
| Нагрузка: запросы к Бэкенду | `days[].requests` |
| Доля ошибок сервера | `days[].errors5xx / requests` |
| Время ответа | `days[].p50Millis`, `p95Millis` |
| Запросы по разделам API | `routes` |

Eight sums of `stat_daily` (two per metric) and one for `collectedSince`.

## System

`GET /api/admin/system/sport` → `AdminSportStatus`:

```
AdminSportStatus {runs: AdminSportRun[] (last 50, newest first), outcomes7d: {SUCCESS, PARTIAL, FAILED},
                  errors7d: {AUTH, NETWORK, HTTP, MAPPING, PERSISTENCE, INTERNAL}, averageDurationMillis7d: long|null,
                  lastSuccessAt: instant|null, activeAutoSignEntries: long, activeFreeSignEntries: long}
AdminSportRun {id: long, timestamp: instant, outcome, durationMillis: long, receivedLessons: int,
               newLessonsAdded: int, updatedLessons: int, skippedLessons: int, errorCategory: string|null}
```

`GET /api/admin/system/sport/settings` -> `AdminSportSettings {autoSignLimit:
int, overridden: boolean, updatedAt: instant|null}`: every user's monthly
auto-sign limit ([sport automation](sport-automation.md#routes)), the default
10 with `overridden = false` until an admin stores one. `PUT` takes
`{autoSignLimit: int}`, an integer `0..100`; a value out of range is 400
`invalid_request_data`, a missing or non-integer one 400 `invalid_request`. The
value is stored in `app_settings` under `sport.auto_sign_limit` and applies to
everyone at once; an unchanged request writes nothing.

`GET /api/admin/system/app-version?platform=ANDROID|IOS` → `AdminAppVersion
{latest, minimum, note, overridden: boolean, updatedAt: instant|null}` of that
platform. `PUT` with the same parameter takes `{latest, minimum, note?}`:
versions are dotted numbers with an optional `-suffix`, `minimum` must not
exceed `latest`, the note is plain text up to 500 characters. `platform`
defaults to `ANDROID`, so a call without it reads and writes the Android values;
any other value is 400 `invalid_request`. Values are stored in `app_settings`
under the platform's keys and served at once by
[`/api/app/version-info`](app-version.md); an unchanged request writes nothing.

`GET /api/admin/system/client-versions` -> `AdminClientVersions`, the devices
active in the rolling last 7 and 30 days by the build they last reported:

```
AdminClientVersions {last7d: AdminClientVersionWindow, last30d: AdminClientVersionWindow}
AdminClientVersionWindow {activeDevices: long, unknownDevices: long, builds: AdminClientBuild[]}
AdminClientBuild {platform: "ANDROID"|"IOS", distribution: string, version: string, build: int, devices: long}
```

A device is active when it registered (`devices.last_login`) or reported a
build (`app_version_seen_at`) in the window, and counts under the last build it
reported, even if that report is older than the window. `unknownDevices` are
the active devices that never reported a build: Android 2.2 and older, whose
only activity signal is registration, so they are undercounted against 2.3
devices that report on every request. `activeDevices` is `unknownDevices` plus
every build's `devices`; `builds` are sorted by `devices`, then by `build`,
both descending. Counts only: no user or device is named.

### Clients

`GET /api/admin/system/clients` -> `AdminClients`, the clients of signed-in
users from [client statistics](clients.md):

```
AdminClients {kinds: AdminClientKind[], history: AdminClientDay[]}
AdminClientKind {kind: "ANDROID"|"IOS"|"WEB"|"AGENT", active1d: long, active7d: long, active30d: long,
                 builds: AdminClientBuildGroup[], platforms: AdminClientCount[]}
AdminClientBuildGroup {custom: boolean, version: string, channel: string|null, source: string|null,
                       active7d: long, active30d: long}
AdminClientCount {name: string|null, active30d: long}
AdminClientDay {day: date, kind, custom: boolean, version: string, active1d: long, active7d: long, active30d: long}
```

Kinds without a client in the last 30 days are left out. `builds` group the
last 30 days by official or custom, group version (a custom build's `base`),
channel and source: official first, then by `active30d` descending.
`platforms` count OS versions of apps, agents and MCP servers and browsers of the web
over 30 days. `history` is the nightly totals of the last 90 days per kind,
custom flag and group version. Counts only: no user or instance is named.

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

### Sheet rules

`GET /api/admin/system/sheet-rules` -> `AdminSheetRules`, the state of the
[sheet rules](../ops/sheet-rules.md) now:

```
AdminSheetRules {enabled: boolean, running: boolean, runningSince: instant|null,
                 statuses: {PENDING, RUNNING, READY, INSUFFICIENT, FAILED} (long), attempts: long,
                 lastAnalyzedAt: instant|null, errors: AdminSheetRuleError[],
                 budgetDay: date, budgetUsed: int, dailyBudget: int}
AdminSheetRuleError {error: string, sheets: long}
```

- `enabled` mirrors `SHEET_RULES_ENABLED`, `dailyBudget` the configured
  `SHEET_RULES_DAILY_BUDGET` (0 when not set).
- `running` is true while the job holds its lease, since `runningSince`.
- `statuses` has every status; `attempts` is the sum of the sheets' failed
  attempts since their last verdict; `lastAnalyzedAt` is the latest verdict.
- `errors` groups the `FAILED` sheets by their last reason code (`closed`,
  `timeout`, `not_shared`, ... of the ops page), the most frequent first, then
  by code, at most 10.
- `budgetDay` is today in Europe/Moscow and `budgetUsed` the analyses spent on
  it, 0 when the stored budget belongs to an earlier day.

No sheet id, URL or rule is returned. Two aggregate queries over `sheet_rules`
and the `sheet_rule_state` row.

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
| `SPORT_SETTINGS_CHANGED` | `sport-settings` | `autoSignLimit 10 -> 15` |
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
