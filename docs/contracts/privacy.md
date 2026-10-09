# Privacy and capabilities

Wire changes follow the [compatibility rule](compatibility.md).

Every user owns three independent audiences: schedule, sport and friends:

- `ALL` — any authenticated caller, regardless of that caller's own settings;
- `FRIENDS` — accepted mutual friends only; the schedule and sport default;
- `NOBODY` — the owner only. Self reads always succeed.

There is no reciprocity and no block model yet. A rejected or cancelled friend
request is not a block; a future block model must be checked before granting
either `ALL` or `FRIENDS` access.

## Own settings

- `GET /api/users/me/privacy` → `ApiResponse<UserPrivacySettings>` with
  `scheduleVisibility`, `sportVisibility` and `friendsVisibility`.
- `PUT /api/users/me/privacy` requires all three fields with exact enum strings and
  returns the updated settings. Missing, null or unknown values produce HTTP 400
  without changing anything.

Raw audiences are returned only here, never in another user's data.

## Viewer capabilities

`UserData.name` is empty until the owner uploads the id token (the access token
carries no `name` claim); clients render their own placeholder, never Backend text.
Every public `UserData` carries `capabilities { canViewSchedule, canViewSport, canViewFriends }`
computed for the authenticated viewer. `/api/users/me/data` returns all as
`true` even when the owner chose `NOBODY`. Consumers must treat missing
capabilities as an error, not as access.

Friends default to `ALL` for existing and new accounts (V3). Older privacy PUT
payloads without the third field fail closed with 400, never reset a saved choice.

Enforcement points:

- `GET /api/users/{isu}/friends` checks the friends audience before loading
  accepted friendships; outgoing/incoming requests never appear;

- target schedule reads check the audience before loading lessons;
- `GET /api/schedule/lessons/{pairId}/friends?date=` returns only the viewer's
  accepted friends who attend that occurrence and whose schedule audience admits
  the viewer, never the viewer, never non-friends with an `ALL` audience; the
  former unrestricted participant list is gone;
- `GET /api/sport/users/{isu}/bookings` returns confirmed current and upcoming
  `lessonIds` plus `entries` (uncancelled `WAITING`/`NOTIFIED` free and auto
  queue entries, the same shape as `FriendSportBooking.entry`); denied access
  reads neither source;
- the friends-sport feed stays bounded to friends and filters each owner's
  audience; confirmed sync is independent of visibility.

## Storage

`user_settings.user_id` is both the primary key and a foreign key to `users.id`;
deleting a user deletes the settings, never the reverse. The persisted choices
are the required `VARCHAR(16)` columns `schedule_visibility` and
`sport_visibility`, defaulting to `FRIENDS` in SQL and Kotlin. V3 adds
`friends_visibility`, defaulting to `ALL`, with a required enum check.

Every bearer request resolves its caller read-first: one `SELECT` of the user
id joined with its settings row. Only on a miss does registration run, in one
short transaction: `INSERT users ON CONFLICT (isu) DO NOTHING`, load the winning
row, insert settings if absent; a user whose settings row is missing is completed
the same way. Repeated registration preserves audiences and auto-sign quotas.

Each device row also keeps the last app build the device reported in
`X-App-Version` and when ([client version header](app-version.md#client-version-header)):
version name, version code, platform and distribution. It exists to see which
builds are in use and when the old ones are gone; it is shown only to admins,
never to the user or to others. A new report overwrites it, so no history is
kept, and it is deleted with the device (unregistration, an `UNREGISTERED` FCM
token) or with the account.

## Authentication

- A request is authenticated by an ITMO.ID access token (`Authorization:
  Bearer`) or a web session ([web login](web.md)). Backend checks the token's
  signature against ITMO.ID's key set, the issuer and the expiry (60 s leeway),
  takes the caller from the `isu` claim and stores no token.
- Missing or invalid credentials make the request anonymous; a protected route
  then answers 401 `unauthorized`. Every privacy, role, moderation and `csrf`
  denial stays 403 ([status codes](compatibility.md#status-codes)).
- The token's client (`azp`) is checked against `id.itmo.allowed-clients`:
  `student-personal-cabinet` and `student-personal-cabinet-dev`, the MyITMO
  clients the app signs in with (MyItmoApi `MyItmoConfiguration`). With
  `id.itmo.azp-mode=log`, the shipped setting, every client is accepted and
  Backend logs one INFO line per hour with the clients seen since the last one,
  `azp counts: {student-personal-cabinet: 12}` (client ids and counts only;
  `(none)` for a token without `azp`), readable with
  `ssh alllexey.dev platform logs <stack>`.
- `id.itmo.azp-mode=enforce` rejects any other client as an invalid token (401).
  It is the owner's configuration switch, made only once production counts show
  nothing but listed clients; a new client (iOS, a web sign-in) joins the list
  first.
