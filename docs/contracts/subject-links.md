# Subject links and moderation

Authenticated students keep HTTPS links per subject and period and share them
with their group, their lecture flow or everybody. Backend never fetches a
submitted URL. MyITMO credentials and refresh tokens are not part of this
feature.

## Link model

A link belongs to one owner and one `(subjectId, periodKey)`. The period key is
`YYYY-S`: `YYYY` is the start of the academic year, `S` is 1 for
September–January and 2 for February–August (`AcademicPeriods.periodKey`).
The subject and period of a link never change; `subjectName` (1–200 characters)
is display text the owner may update.

| Field | Values |
|---|---|
| `category` | `SCORES`, `QUEUE`, `MATERIALS`, `TASKS`, `RECORDINGS`, `NOTES`, `EXAM`, `CHAT`, `OTHER` |
| `visibility` | `PRIVATE`, `GROUP`, `FLOW`, `ALL` |
| `title` | optional, trimmed, at most 120 characters; blank means none |
| `url` | see [URL policy](#url-policy) |

A chat is an ordinary link with category `CHAT`; clients show it separately.
Enum values are exact name strings: numbers, unknown names and missing fields
are HTTP 400 before any service code runs.

## Audiences and flow membership

`GROUP` and `FLOW` resolve to schedule flows (`flow_id`) of the author in that
subject and period, so two intakes with the same group name never share links:

- `GROUP` — every flow of the author with `type_id != 1` (practice, labs);
- `FLOW` — every flow with `type_id == 1` (lectures).

The flows come from `FlowMembership`. The current implementation,
`ScheduleFlowMembership`, trusts the schedule the user uploaded:
`LessonService.syncLessons` records each lesson's flow in `user_subject_flows`,
and the rows stay after the lessons are removed. A strict membership check
(ISU or zkTLS) is a separate task and would replace only this implementation.

Saving a `GROUP` or `FLOW` link snapshots the author's current matching flows
into `subject_link_audience`. No matching flow means HTTP 400
`invalid_request_data` with the message `audience_unavailable`, and nothing is
saved. Saving `PRIVATE` or `ALL` keeps the last snapshot, so a group link that
is being widened to everybody stays visible to its group until the review.
A viewer sees a `GROUP` or `FLOW` link when `FlowMembership.sharesAny` finds
one of the snapshotted flows among the viewer's flows of the link's period.

## Revisions and review

The link row holds the owner's current content. Every change of a non-private
link's category, URL, title or visibility creates an immutable revision
(`subject_link_revisions`, sequential `number`); a pending revision of the same
link is withdrawn first. Content other students see is always the **latest
approved revision**, never the link row:

| New visibility | Revision | Case |
|---|---|---|
| `PRIVATE` | none; a pending revision is withdrawn | its open case is withdrawn |
| `GROUP`, `FLOW` | `APPROVED` at once | resolved `SUBMISSION` case with an `APPROVE` decision, `actor=POLICY`, no moderator |
| `ALL`, premoderation off | `APPROVED` at once | same policy decision |
| `ALL`, premoderation on | `PENDING` | open `SUBMISSION` case |

The approved revision's visibility decides the audience, so a group link
widened to `ALL` is not public before a moderator approves it. Editing an
approved public link leaves everybody on the old content until the decision; a
rejected edit leaves them there. Switching a link to `PRIVATE` hides it from
everybody at once. Saving identical content creates no revision.

Revision statuses are `PENDING`, `APPROVED`, `REJECTED`, `WITHDRAWN`; at most one
revision per link is pending.

### Status

Owners see the status of their own links; everybody else only receives
`PUBLISHED` content.

| Status | Condition, in this order |
|---|---|
| `HIDDEN` | a moderator hid the link |
| `PRIVATE` | the link is private |
| `PENDING` | the newest revision waits for review |
| `REJECTED` | the newest revision was rejected; `reviewNote` is the decision note |
| `PUBLISHED` | otherwise |

## Lists

`GET /api/subjects/{subjectId}/links?period=` returns `SubjectLinksResponse`:

- `mine` — the viewer's links in any state with their current content;
- `shared` — other owners' links the viewer sees: not hidden, not private, with
  an approved revision whose audience admits the viewer. Links with the same
  normalized URL collapse into the one with the higher `score` (earlier link on
  a tie). `GROUP`/`FLOW` links come first, then `score` descending, then age;
- `previous` — at most 10 approved `ALL` links of `MATERIALS`, `TASKS`,
  `RECORDINGS`, `NOTES`, `EXAM` from earlier periods of the subject, by
  `score`, duplicates collapsed;
- `pinnedId` — the viewer's pin for this subject and period if that link is in
  one of the lists, otherwise null;
- `audiences` — `LinkAudience(GROUP|FLOW, label)` the viewer can publish to now;
  empty lists are omitted;
- `premoderation` — whether `ALL` links wait for review.

`audienceLabel` and `LinkAudience.label` list the group names of the flows once
each, joined with `", "` (a lecture flow names several groups). For a link the
label comes from the author's snapshotted flows; it is null for `PRIVATE` and
`ALL`.

## Routes

Responses use `ApiResponse<T>`; the actor always comes from authentication and
every route is 403 anonymously. Authors in responses pass through
`CurrentStudyGroupsService.userData` after the service transaction.

| Route | Body | Response data |
|---|---|---|
| `GET /api/subjects/{subjectId}/links?period=YYYY-S` | — | `SubjectLinksResponse` |
| `PUT /api/links/{id}` | `SaveSubjectLinkRequest` | `SubjectLink` (owner view) |
| `DELETE /api/links/{id}` | — | empty object |
| `PUT /api/links/{id}/saved` | `SetLinkSavedRequest` | `SubjectLink` |
| `PUT /api/subjects/{subjectId}/links/pin` | `PinSubjectLinkRequest` | `SubjectLinksResponse` |
| `PUT /api/links/{id}/vote` | `ResourceVoteRequest` | `SubjectLink` |
| `POST /api/links/{id}/report` | `ModerationReportRequest` | `SubjectLink` |
| `GET /api/users/me/restrictions` | — | active `UserRestriction[]` |

- `PUT /api/links/{id}` creates the link under the client-generated UUID or
  edits the caller's own link. Another owner's link is 403
  `permission_denied`; a different subject or period is 400.
- `DELETE` removes the caller's own link (403 for another owner, no-op when it
  does not exist). Open cases of its revisions are withdrawn and their reports
  removed; votes, saves, pins and audiences cascade.
- `saved` adds another owner's visible link to the caller's list or removes it
  (`isSaved`). Own links are 409; links the caller does not see are 404.
- `pin` accepts only a link from the caller's own lists for that subject and
  period (404 otherwise); an absent `linkId` clears the pin.
- `vote` accepts `-1`, `1` (replaces the previous vote) and `0` (removes it).
  Own links are 409, invisible ones 404. The link's `score` is the sum of votes.
- `report` reports the revision the caller currently sees; own links are 409.

### DTOs

| Type | Fields |
|---|---|
| `SubjectLink` | `id`, `subjectId`, `subjectName`, `periodKey`, `category`, `url`, `title?`, `visibility`, `audienceLabel?`, `status`, `reviewNote?`, `score`, `myVote` (-1/0/1), `isMine`, `isSaved`, `reportedByMe`, `author?` (`UserData`, null on own links), `updatedAt` |
| `SubjectLinksResponse` | `mine`, `shared`, `previous`, `pinnedId?`, `audiences`, `premoderation` |
| `LinkAudience` | `visibility`, `label` |
| `SaveSubjectLinkRequest` | `subjectId`, `subjectName`, `periodKey`, `category`, `url`, `title?`, `visibility` |
| `SetLinkSavedRequest` | `saved` |
| `PinSubjectLinkRequest` | `periodKey`, `linkId?` |
| `ResourceVoteRequest` | `value` |
| `SubjectLinkRevision` | `id`, `linkId`, `number`, `category`, `url`, `title?`, `visibility`, `status`, `submittedAt`, `decidedAt?`, `note?` |
| `SubjectLinkTarget` | `targetType: "SUBJECT_RESOURCE"`, `revision`, `link`, `author`, `reports`, `submitterHistory` |

For the owner `SubjectLink` carries the current content and `updatedAt` of the
link row; for others the approved revision's content and decision time. Null
optional fields are serialized as `null`.

## Limits and restrictions

- Saving a non-private link requires the `SUBMIT_RESOURCES` capability; voting
  requires `VOTE`, reporting `REPORT`. A restricted action is 403 `restricted`.
  Private links, deletion, saving others' links and pins stay available.
- Every new revision counts against `dailySubmissionLimit` (default 20) over a
  rolling 24 hours; the limit is 409. Saving without a content change does not
  count.
- Reports follow `dailyReportLimit` and are unique per revision and reporter.

## Moderation

Case targets are revisions (`targetType = SUBJECT_RESOURCE`). `SubjectLinkService`
implements `ModerationTarget`; reports, roles, cases, settings, decisions and
restrictions are the shared machinery and know nothing about links.

| Action | Effect on the revision's link |
|---|---|
| `APPROVE` | a pending revision becomes the shown content; an approved one stays as is |
| `REJECT` | a pending or approved revision is rejected; others fall back to the previous approved content, if any |
| `HIDE` / `RESTORE` | sets / clears `hidden_at`; a hidden link is shown to nobody but its owner (`HIDDEN`) |
| `DISMISS` | dismisses the revision's active reports |
| `RESTRICT_USER` | restricts the author (handled by `ModerationService`) |
| `HIDE_ALL_BY_USER` | hides every published link of the author and rejects their pending revisions; private links stay; the initiating case stays open, other cases of rejected revisions are withdrawn |

Terminal decisions resolve the case; `RESTRICT_USER` and `HIDE_ALL_BY_USER`
keep it open, and `RESTORE` may append to a resolved case. At most one case is
open per revision. A `REPORTS` case opens once `reportThreshold` distinct active
reporters report the shown revision; a `VOTES` case once the link's score falls
to `voteThreshold`. Switching premoderation off approves every open
`SUBMISSION` case whose link is not hidden, recording the acting moderator.

`SubjectLinkTarget.link` shows the content other students currently see (the
reviewed revision before the first approval) with the owner-side status;
`revision` is the reviewed content. `submitterHistory` counts the author's
approved and rejected revisions, their dismissed reports and active
restrictions. Reporter identities never leave Backend. Deleted links leave
their cases and decisions with `target = null`.

Mutations take the transaction-scoped advisory lock of the target type before
user and link row locks, which serializes threshold counting and policy
switches. Timestamps use the injected `Clock`.

### Settings

`ModerationSettings(policies)` is keyed by target type; a PUT must contain every
type and is validated before any write. Values are stored as
`<TYPE>.<suffix>` rows in `moderation_settings`; missing keys use the defaults.

| JSON field | Suffix | Default | Validation |
|---|---|---|---|
| `premoderation` | `premoderation` | true | boolean |
| `reportThreshold` | `report_threshold` | 3 | at least 1 |
| `voteThreshold` | `vote_threshold` | -3 | at most -1 |
| `dailySubmissionLimit` | `daily_submission_limit` | 20 | at least 1 |
| `dailyReportLimit` | `daily_report_limit` | 10 | at least 1 |

Reads cache a policy for 30 seconds; mutations read the committed policy under
the type lock, and updates clear the cache when the transaction completes.

## URL policy

`ResourceUrlPolicy` is syntax only and accepts any HTTPS host. It rejects other
schemes, user info, ports other than 443, whitespace and control characters, and
input or ASCII forms over 2000 characters. Internationalized hosts are converted
to Punycode.

- Google Sheets `docs.google.com/spreadsheets/d/{id}` normalize to the sheet ID;
  the opened URL keeps a numeric `gid` from the query or fragment as `#gid=`.
- Other links get a lowercase host and lose the fragment, a trailing path slash,
  `utm_*` and `fbclid` parameters; other query parameters stay.

`normalized_url` drives duplicate collapsing in lists; different owners may
keep the same URL.

## Schema

`V4__subject_links.sql` creates the moderation tables (`user_roles`,
`moderation_cases`, `moderation_decisions`, `user_restrictions`,
`moderation_settings`, `moderation_reports`) and `subject_links`,
`subject_link_revisions`, `subject_link_audience`, `subject_link_votes`,
`subject_link_saves`, `subject_link_pins`, `user_subject_flows`. Revisions,
audiences, votes, saves and pins cascade with their link; flows cascade with
their user. Polymorphic case and report targets have no foreign key: the link
service withdraws cases and removes reports when a link is deleted.

## Tests

`SubjectLinkServiceTest` covers visibility by audience and flow, premoderation,
edits under review, hiding, duplicates, previous periods, restrictions, limits,
votes, saves, pins, deletion and author-wide hiding on PostgreSQL.
`SubjectLinkControllerSecurityTest` pins anonymous denial and the exact JSON
keys; `ResourceUrlPolicyTest` the URL syntax; `ModerationServiceTest`,
`ModerationReportServiceTest`, `ModerationSettingsServiceTest`,
`RestrictionServiceTest` and `ModerationControllerSecurityTest` the shared
machinery. `PostgreSqlMigrationTest`, `SubjectLinkPersistenceTest`,
`CommunityModerationPersistenceTest` and `ScheduleFlowMembershipTest` cover the
schema, cascades, concurrent reports and flow recording.
