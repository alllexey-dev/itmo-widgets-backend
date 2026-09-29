# Teacher reviews

Authenticated users read and write reviews of a teacher in the person's profile.
One list holds two kinds: own reviews of ITMO.Widgets users (`COMMUNITY`), which
pass premoderation, and anonymous copies of the
[Reviews project](https://onetwozzzplus.github.io/reviews/) (`REVIEWS`, see
[Reviews sync](../ops/reviews-sync.md)). The teacher need not have an
ITMO.Widgets account. Backend checks through ISU whether the teacher taught the
author ([ISU verification](../ops/isu-verification.md)) and never trusts the
client for it. The routes read only local data; they never call My ITMO,
Reviews or ISU on the caller's behalf.

## Review model

A review is bound only to the teacher's ISU. An author has at most one review
per teacher (`UNIQUE (author_id, teacher_isu)`), edits it and deletes it.

- `teacher_reviews` is the author's row with the current content (subject,
  text), `anonymous`, `score`, `hidden_at` and the state of the ISU check.
- `teacher_review_revisions` are immutable versions of the subject and text
  with the statuses `PENDING`, `APPROVED`, `REJECTED`, `WITHDRAWN`; at most one
  revision per review is pending, and a new revision withdraws the pending one.
  Other viewers always see the **latest approved revision**, never the row.
- Every change of the subject or text creates a revision that waits for a
  moderator: teacher reviews are always premoderated. A published review keeps
  showing its previous approved content until the decision; a rejected edit
  leaves it there.
- `anonymous` lives on the row, not in a revision. Switching it creates no
  revision, needs no `WRITE_REVIEWS`, counts against no limit and applies at
  once. Turning anonymity on hides the name from then on; it does not erase
  what others have already seen. The same holds for a save that only changes
  `flowIds`.
- Deleting a review withdraws the open cases of its revisions, deletes the
  reports on them and the revisions; votes and flow candidates cascade.

### Status

The author sees the status of their own review; everybody else only receives
published content.

| Status | Condition, in this order |
|---|---|
| `HIDDEN` | a moderator hid the review |
| `PENDING` | the newest revision waits for review |
| `REJECTED` | the newest revision was rejected; `reviewNote` is the decision note |
| `PUBLISHED` | otherwise |

The date of an own review is a Europe/Moscow date: for other viewers the day
the shown approved revision was sent, for the author the day their newest
revision was sent.

## Anonymity

`TeacherReview.author` is a viewer-scoped `UserData` only on a `COMMUNITY`
review written under the author's name; on an anonymous review it is `null`,
and no other field names the author. Named authors pass through
`CurrentStudyGroupsService.userData` after the service transaction, like every
profile response. `mine` goes only to its author. Moderators and admins see the
author in moderation cases even for an anonymous review; reporter identities
never leave Backend. A person's profile does not list the reviews they wrote.

## Lists and order

`GET /api/teachers/{isu}/reviews` returns `TeacherReviewsResponse`:

- `reviews` — other authors' published own reviews (not hidden, with an approved
  revision) and the active `REVIEWS_WORK_GD` copies (`removed_at IS NULL`) of
  the teacher, in `ReviewOrder.RANKED`. The viewer's own review is never in it.
- `mine` — the viewer's review with its current content and status, or `null`.
- `canWrite` — the viewer is not the teacher and has no active `WRITE_REVIEWS`
  or `ALL` restriction; `canVote` — the same for `VOTE`; `canReport` — no
  `REPORT` or `ALL` restriction.
- `knownTeacher` — the ISU appears as `teacher_isu` of any loaded lesson, in the
  ISU flow teacher cache, in an active Reviews copy or in a published own review.
  The app uses it to offer writing a review.

`ReviewOrder.RANKED` sorts by:

1. `score` descending.
2. The date descending: `writtenOn`, or January 1 of `writtenBeforeYear`;
   undated reviews last.
3. A real date before a before-year value with the same key.
4. `COMMUNITY` before `REVIEWS`.
5. `id` ascending as a string.

Verification does not affect the order.

## Votes

A vote is +1 or −1 per user and review. Own reviews keep votes in
`teacher_review_votes`, Reviews copies in `external_teacher_review_votes`; the
review's `score` is the sum of its votes and is stored on its row
(`teacher_reviews.score`, `external_teacher_reviews.score`). Copies keep their
UUID across syncs (upsert by provider and external id, removed rows are only
marked), so their votes survive a sync. A score falling to `voteThreshold` opens
a `VOTES` case on the shown revision of an own review; Reviews copies are not
moderation targets. A teacher cannot vote on reviews about themselves, and
nobody votes on their own review (409).

## Reports

A report targets the revision the reporter currently sees. Reasons are
`OFFENSIVE`, `WRONG_TEACHER`, `SPAM` and `OTHER`; the link reasons `BROKEN` and
`WRONG_SUBJECT` are 400 `invalid_request_data`. The comment is optional, at most
500 characters. Reports follow the shared rules: `dailyReportLimit` over a
rolling day, one report per revision and reporter, a `REPORTS` case once
`reportThreshold` distinct active reporters report the shown revision. Reviews
copies and own reviews cannot be reported (409).

## Limits and restrictions

- The teacher ISU of a write is `100000..9999999`; a review of oneself is
  400 `invalid_request_data`.
- The text after `trim()` with `\r\n` replaced by `\n` has 30–3000 characters
  and no control characters except `\n` and `\t`. The subject after `trim()` has
  at most 200 characters; a blank subject becomes `null`. Lengths count code
  points, as `char_length` in PostgreSQL.
- `flowIds` holds at most 50 positive numbers; repeats are dropped, `null` is
  400 `invalid_request_data`.
- A save with a new subject or text needs `WRITE_REVIEWS`, a vote `VOTE`, a
  report `REPORT`; deletion is always allowed. A restricted action is 403
  `restricted`.
- Every new revision counts against the `TEACHER_REVIEW` `dailySubmissionLimit`
  (default 20) over a rolling 24 hours; the limit is 409
  `business_rule_violation`. A save without a content change does not count.

## Verification

Backend sets `verified` when some ISU flow has the teacher in its schedule and
the author among its members; `PENDING` and `UNVERIFIED` both read as
`verified = false`. Candidate flows are the author's loaded lessons with the
teacher, the `flowIds` of the latest save and the author's schedule flows. A
review is never rejected because of ISU, and an `UNVERIFIED` review goes back to
`PENDING` on any save by its author. A save queues the check after it commits.
Details are in [ISU verification](../ops/isu-verification.md).

## Routes

All successful responses use `ApiResponse<T>`; the actor always comes from
authentication and every route is 403 anonymously. Cookie requests other than
GET need `X-Web-Request: 1` ([web login](web.md)). Every route answers with the
fresh `TeacherReviewsResponse` of the review's teacher for the caller. There is
no pagination or rate limiter.

| Route | Body | Response data |
|---|---|---|
| `GET /api/teachers/{isu}/reviews` | — | `TeacherReviewsResponse` |
| `PUT /api/teachers/{isu}/reviews/mine` | `SaveTeacherReviewRequest` | `TeacherReviewsResponse` |
| `DELETE /api/teachers/{isu}/reviews/mine` | — | `TeacherReviewsResponse` |
| `PUT /api/reviews/{id}/vote` | `ResourceVoteRequest` | `TeacherReviewsResponse` |
| `POST /api/reviews/{id}/report` | `ModerationReportRequest` | `TeacherReviewsResponse` |

- `GET` for an unknown positive ISU is HTTP 200 with empty lists, not 404.
- `PUT …/mine` creates the caller's review or edits it. A missing `anonymous`
  means `true`; anything but a JSON boolean makes the body unreadable.
- `DELETE …/mine` without a review is a no-op.
- `vote` accepts `-1`, `1` (replaces the previous vote) and `0` (removes it) on
  another author's published review or an active copy.

| Condition | HTTP | Error code |
|---|---|---|
| Anonymous caller | 403 | denied by Spring Security before the service |
| `GET`/`DELETE` with `isu <= 0`, `PUT` outside `100000..9999999` or of oneself | 400 | `invalid_request_data` |
| Text, subject, `flowIds`, vote value or report reason out of the rules above | 400 | `invalid_request_data` |
| Nonnumeric path ISU, malformed UUID, unreadable body, unknown enum name | 400 | `invalid_request` |
| Restricted capability | 403 | `restricted` |
| Unknown id, hidden or never approved own review, removed copy | 404 | `not_found` |
| Own review or a review about oneself, reporting a copy, repeated report, daily limits | 409 | `business_rule_violation` |

### DTOs

| DTO | Fields |
|---|---|
| `TeacherReviewsResponse` | `teacherIsu: Int`, `providerUrl: String`, `reviews: List<TeacherReview>`, `mine: OwnTeacherReview?`, `canWrite`, `canVote`, `canReport`, `knownTeacher: Boolean` |
| `TeacherReview` | `id: UUID`, `kind: COMMUNITY\|REVIEWS`, `subjectTitle: String?`, `writtenOn: LocalDate?`, `writtenBeforeYear: Int?`, `text: String`, `score: Int`, `myVote: Int` (-1/0/1), `verified: Boolean`, `reportedByMe: Boolean`, `author: UserData?`, `sourceTitle: String?`, `sourceLink: String?` |
| `OwnTeacherReview` | `id: UUID`, `subjectTitle: String?`, `text: String`, `anonymous: Boolean`, `status: PENDING\|PUBLISHED\|REJECTED\|HIDDEN`, `reviewNote: String?`, `score: Int`, `verified: Boolean`, `writtenOn: LocalDate` |
| `SaveTeacherReviewRequest` | `subjectTitle: String?`, `text: String`, `anonymous: Boolean` (default `true`), `flowIds: List<Long>` (default empty) |
| `ResourceVoteRequest` | `value: Int` |
| `ModerationReportRequest` | `reason`, `comment?` |
| `TeacherReviewRevision` | `id`, `reviewId`, `number`, `subjectTitle?`, `text`, `status: PENDING\|APPROVED\|REJECTED\|WITHDRAWN`, `submittedAt`, `decidedAt?`, `note?` |
| `ModeratedTeacherReview` | `id`, `teacherIsu`, `teacherName?`, `anonymous`, `status`, `reviewNote?`, `shown: TeacherReviewRevision?`, `score`, `hidden`, `verification: PENDING\|VERIFIED\|UNVERIFIED`, `verifiedFlowId: Long?` |
| `TeacherReviewTarget` | `targetType: "TEACHER_REVIEW"`, `revision`, `review: ModeratedTeacherReview`, `author: UserData`, `reports`, `submitterHistory` |

A `COMMUNITY` review has no `sourceTitle`, `sourceLink` or `writtenBeforeYear`.
A `REVIEWS` copy has `author = null`, `verified = false` and
`reportedByMe = false`; its `id` is the copy's stable UUID, not the upstream
identifier, and at most one of its date fields is set. Nullable fields are
serialized as JSON `null`; dates are ISO dates such as `2025-01-25`.
`TeacherReviewRevision` and `ModeratedTeacherReview` appear only in moderation
cases; `ModeratedTeacherReview.teacherName` is filled from My ITMO by the web
admin layer and is never stored ([admin API](admin.md#moderation)).

`providerUrl` is the Reviews page for the requested ISU:
`https://onetwozzzplus.github.io/reviews/#/teacher/{isu}`. A copy's optional
`sourceTitle` and `sourceLink` identify the original source; Backend preserves
them rather than manufacturing a source or a date. The response never includes
the stored teacher name of a copy, its external id, raw date or persistence
timestamps.

In the 1.7.0-SNAPSHOT cycle this response replaced the earlier `external` list
of copies with the ordered `reviews` and added `mine`, `canWrite`, `canVote`,
`canReport` and `knownTeacher`. A client built for `external` reads no reviews
from this Backend.

## Moderation

Case targets are revisions (`targetType = TEACHER_REVIEW`).
`TeacherReviewService` implements `ModerationTarget`; reports, cases, settings,
decisions and restrictions are the shared machinery of
[subject links](subject-links.md#moderation).

| Action | Effect |
|---|---|
| `APPROVE` | a pending revision becomes the shown content; an approved one stays as is |
| `REJECT` | a pending or approved revision is rejected; others fall back to the previous approved content, if any |
| `HIDE` / `RESTORE` | sets / clears `hidden_at`; a hidden review is shown to nobody but its author (`HIDDEN`) |
| `DISMISS` | dismisses the revision's active reports |
| `RESTRICT_USER` | restricts the author (handled by `ModerationService`); the web admin suggests `WRITE_REVIEWS` |
| `HIDE_ALL_BY_USER` | hides every published review of the author and rejects their pending revisions; the initiating case stays open, other cases of rejected revisions are withdrawn |

A new revision opens a `SUBMISSION` case; there is no automatic approval.
`PUT` of the moderation settings with `TEACHER_REVIEW.premoderation = false` is
400 `invalid_request_data`; the other `TEACHER_REVIEW` thresholds and limits are
edited like those of links. `TeacherReviewTarget.review.shown` is the content
others see now (null before the first approval), `revision` the reviewed one;
`submitterHistory` counts the author's approved and rejected review revisions,
their dismissed reports and active restrictions. Deleted reviews leave their
cases and decisions with `target = null`.

## Schema

`V9__teacher_reviews.sql` adds `teacher_reviews`, `teacher_review_revisions`,
`teacher_review_votes`, `external_teacher_review_votes`, `teacher_review_flows`
(the `flowIds` of the latest save) and the ISU cache `isu_potoks`,
`isu_potok_teachers`, `isu_potok_members`; it adds
`external_teacher_reviews.score` (`DEFAULT 0`), the partial index
`idx_lessons_teacher` and widens the target type and report reason checks of
`moderation_cases` and `moderation_reports`. Revisions, votes and flows cascade
with their review, reviews with their author. Polymorphic case and report
targets have no foreign key: the review service withdraws cases and removes
reports when a review is deleted.

## Tests

`TeacherReviewServiceTest` covers the lists, anonymity, statuses, premoderation,
edits under review, anonymity switches, limits, restrictions, votes on both
kinds, reports, deletion and author-wide hiding on PostgreSQL.
`TeacherReviewControllerSecurityTest` pins anonymous denial, the web-request
header, strict `anonymous`, error mapping and the exact JSON keys, and that an
anonymous author is never named. `ReviewOrderTest` covers `RANKED`;
`TeacherReviewPersistenceTest` and `ExternalTeacherReviewPersistenceTest` the
schema, cascades and active-row filtering; `ModerationReportServiceTest` the
review report reasons.
