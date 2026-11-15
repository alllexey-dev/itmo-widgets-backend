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
| `GET /api/schedule/friends?date=` | — | `List<FriendDay>` |

## Snapshot upload

`POST /api/schedule/lessons/sync` replaces the caller's lessons between `from`
and `to` inclusive: rows of that range missing from the snapshot are deleted, the
rest are upserted by `(user_isu, pair_id)`. An empty list clears the range. The
whole request is rejected with 400 `invalid_request_data` when `from` is after
`to`, a lesson lies outside the range, a `pairId` repeats, the snapshot has more
than 2000 lessons, or a lesson text is too long: `subjectName`, `groupName`,
`teacherFio`, `room` and `building` over 1000 characters, `note` over 2000,
`type` or `format` over 100. Production holds at most 718 lessons per user in
total, and MyITMO texts are far shorter. The owner's row is
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
caller ([study groups](friendships.md#study-groups)).

`GET /api/schedule/friends?date=YYYY-MM-DD` answers what the caller's friends
have on one day: `FriendDay {user: UserData, lessons: List<LessonDto>}`, one
per accepted friend whose schedule audience admits the caller and who has a
lesson on `date`, in the order of `GET /api/friends` (most recently accepted
first), with that friend's lessons of the day ordered by start. A friend who
hides the schedule or has no lesson that day is left out, never marked, so the
answer says nothing the caller could not read through
`GET /api/schedule/lessons/user/{isu}` friend by friend. `date` is a
Europe/Moscow day, today when omitted; any day is allowed, and a malformed one
is 400 `invalid_request`. Backend returns the day's lessons and the client
derives the current lesson, the break and the next one from its own clock, so
the answer stays the same for the whole day. The whole friend list is one read:
the friend list, the friends with their settings and groups, and their lessons
by the `(user_isu, date)` index, three queries whatever the number of friends.
The app's bearer and the web (`user` scope) may call it; an agent or an MCP
server may not, whatever its scopes ([connected clients](connections.md#scopes)).

## Tests

`ScheduleControllerSecurityTest` (anonymous denial and audiences),
`LessonSyncConcurrencyTest` (overlapping uploads on PostgreSQL),
`LessonContextServiceTest` (friends on a lesson and of a day),
`FriendsOnDayPersistenceTest` (friends of a day on PostgreSQL: filters, order,
the Moscow default day, a statement count independent of the friend count).
