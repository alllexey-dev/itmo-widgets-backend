# Schedule

Wire changes follow the [compatibility rule](compatibility.md).

The app reads the schedule from MyITMO itself and uploads a snapshot of the
signed-in user's lessons, so that people the owner's schedule audience admits
can read it ([privacy](privacy.md)). Backend never fetches a user's schedule
from MyITMO.

## Routes

All routes require authentication; identity comes from the authenticated UUID.
Responses use the `ApiResponse<T>` envelope.

| Route | Body | Response data |
|---|---|---|
| `POST /api/schedule/lessons/sync` | `LessonSyncRequest {lessons: List<LessonDto>, from, to}` (ISO dates) | `String` |
| `GET /api/schedule/lessons/user/{isu}?from=&to=` | — | `List<LessonDto>`, ordered by date and start |
| `GET /api/schedule/lessons/{pairId}/friends?date=` | — | `List<UserProfile>` |

## Snapshot upload

`POST /api/schedule/lessons/sync` replaces the caller's lessons between `from`
and `to` inclusive: rows of that range missing from the snapshot are deleted, the
rest are upserted by `(user_isu, pair_id)`. An empty list clears the range. The
whole request is rejected with 400 `invalid_request_data` when `from` is after
`to`, a lesson lies outside the range or a `pairId` repeats. The owner's row is
locked before either change, so overlapping uploads serialize. Each upload also
records the caller's subject flows per academic period, which subject-link
audiences use ([subject links](subject-links.md)); removing lessons never
removes a flow.

## Reads

`GET /api/schedule/lessons/user/{isu}` checks the owner's schedule audience
before loading lessons and answers 403 `permission_denied` when it does not
admit the caller; self reads always succeed. An unknown ISU is 404.

`GET /api/schedule/lessons/{pairId}/friends?date=` lists the caller's accepted
friends who attend that occurrence and whose schedule audience admits the
caller, with current study groups ([friendships](friendships.md)).

## Tests

`ScheduleControllerSecurityTest` (anonymous denial and audiences),
`LessonSyncConcurrencyTest` (overlapping uploads on PostgreSQL),
`LessonContextServiceTest` (friends on a lesson).
