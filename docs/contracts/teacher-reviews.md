# Teacher reviews

Authenticated users can read Backend's anonymous copy of reviews from the
[Reviews project](https://onetwozzzplus.github.io/reviews/) in a person's
profile. The teacher need not have an ITMO.Widgets account. The endpoint reads
only the local copy; it never fetches My ITMO or Reviews on the caller's behalf.
See [Reviews sync](../ops/reviews-sync.md) for how the copy is maintained.

## Routes

All successful responses use `ApiResponse<T>`.

| Route | Authentication | Response data |
|---|---|---|
| `GET /api/teachers/{isu}/reviews` | ITMO.ID bearer token or `iw_session` cookie | `TeacherReviewsResponse` |

A cookie-authenticated GET does not require `X-Web-Request`. There is no
pagination or rate limiter. An unknown positive ISU returns HTTP 200 with an
empty `external` list, not 404.

| Condition | HTTP | Error code |
|---|---|---|
| Anonymous caller | 403 | Denied by Spring Security before the service |
| `isu <= 0` | 400 | `invalid_request_data` (`ISU must be positive`) |
| Nonnumeric or out-of-range path parameter | 400 | `invalid_request` |

## DTOs

| DTO | Fields |
|---|---|
| `TeacherReviewsResponse` | `teacherIsu: Int`, `providerUrl: String`, `external: List<ExternalTeacherReview>` |
| `ExternalTeacherReview` | `id: UUID`, `subjectTitle: String?`, `writtenOn: LocalDate?`, `writtenBeforeYear: Int?`, `sourceTitle: String?`, `sourceLink: String?`, `text: String` |

`id` is the copy's stable UUID, not the upstream identifier. `subjectTitle` is
free text. Nullable fields are serialized as JSON `null`; `writtenOn` is an ISO
date such as `2025-01-25`. At most one date field is non-null, and both may be
null. These reviews contain text only: no author, rating or score is exposed.

The response never includes the stored teacher name, external review id, raw
date, provider identifier or persistence timestamps (`firstSeenAt`,
`lastSeenAt`, `removedAt`).

`providerUrl` is the Reviews page for the requested ISU:
`https://onetwozzzplus.github.io/reviews/#/teacher/{isu}`. The optional
`sourceTitle` and `sourceLink` identify the original source. Android labels the
link `Reviews · <sourceTitle>` (or `Reviews`), opens a valid HTTPS `sourceLink`
and otherwise falls back to `providerUrl`. Backend preserves the nullable
source fields rather than manufacturing a source or a date.

## Selection and order

Only `REVIEWS_WORK_GD` rows with `removed_at IS NULL` and the requested
`teacher_isu` are returned. `ReviewOrder.NEWEST_FIRST` sorts by:

1. `writtenOn`, or January 1 of `writtenBeforeYear`, descending.
2. A real date before a before-year value with the same key.
3. Undated reviews last; equal keys break by descending internal `externalId`.

Thus `2024-01-01` precedes `before 2024`, which precedes `2023-12-31`.
The read service runs in a read-only transaction and uses the existing V7
`idx_external_teacher_reviews_teacher` partial index. This API adds no migration.

## Extension boundary

Writing reviews, eligibility verification, local review moderation, votes and
summaries are not implemented. They will extend `TeacherReviewsResponse`
rather than replace the read endpoint or introduce a separate teacher screen.

## Tests

`ReviewOrderTest`, `ExternalTeacherReviewPersistenceTest`,
`TeacherReviewServiceTest` and `TeacherReviewControllerSecurityTest` cover
ordering, active-row filtering on PostgreSQL, nullable mapping, invalid and
unknown ISUs, authentication by user context and cookie, and exact wire keys.
