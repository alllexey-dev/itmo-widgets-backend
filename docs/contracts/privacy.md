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
`UserData.gender` (`male` or `female` as ITMO.ID sends it, for declension) is
null until then too, and so are `familyName` and `givenName`, the token's
`family_name` and `given_name`. With both, `name` is «familyName givenName»;
clients take avatar initials from the two parts, never by splitting `name`.
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
token) or with the account. An iOS build without push has no token to
register, so its first report creates its device row with only that build and
a generic name ([devices without push](app-version.md#devices-without-push));
the row is deleted with the account.

Every client of a signed-in user (app install, agent, MCP server, web) is also
recorded in `client_instances` with its build, OS major version or browser
([client statistics](clients.md)), shown only to admins and deleted 180 days
after its last request or with the account.

### ITMO.ID session ids

Backend stores the `sid` of ITMO.ID sessions, never a token or cookie of them:

- of every [connected client](connections.md) that has its own session (agent,
  MCP), as the phone reported it at the approval, with its end; a revoked
  connection keeps it until the phone reports the session ended or it expires;
- of every app install, from the ID token of its `PUT /api/users/me/id-token`
  (`client_instances.itmo_sid`).

They let the phone end a client's session and match `id.itmo.ru` sessions to
its connections and installs; only the owner sees them (`GET
/api/users/me/devices`), and they go with the connection, the install's row or
the account. An ITMO.ID access token of a connected client's own session is
refused ([`client_session`](connections.md#the-client_session-rule)). The IP
addresses, labels and times of ITMO.ID sessions stay on the phone.

## Authentication

- A request is authenticated by an ITMO.ID access token (`Authorization:
  Bearer`) or a connection token ([connected clients](connections.md): `iwc_`
  bearer or the web's `iw_client` cookie). Backend checks the ITMO.ID token's
  signature against ITMO.ID's key set, the issuer and the expiry (60 s leeway),
  takes the caller from the `isu` claim and stores no token.
- Missing or invalid credentials make the request anonymous; a protected route
  then answers 401 `unauthorized`. Every privacy, role, moderation and `csrf`
  denial stays 403 ([status codes](compatibility.md#status-codes)).
- A bearer starting with `iwc_` is a connection token, never an ITMO.ID token:
  an agent or MCP server reaches only the routes its scopes list, never privacy,
  account, pairing approval, admin or moderation; the web reaches the user's
  routes but the phone-only ones ([scopes](connections.md#scopes)). Backend
  stores only its SHA-256.
- An ITMO.ID bearer of a user who has not turned the services on gets 403
  `not_enrolled` ([enrollment](connections.md#enrollment)); the first bearer
  request no longer creates a user.
- Only an access token authenticates: a token whose `typ` is not `Bearer`
  (an ITMO.ID ID token of the same user, for example) is an invalid token (401).
- The token's client (`azp`) is checked against `id.itmo.allowed-clients`:
  `student-personal-cabinet`, the MyITMO client the app signs in with (MyItmoApi
  `MyItmoConfiguration.DEFAULT`). With `id.itmo.azp-mode=enforce`, the shipped
  setting since 2026-10-20, any other client or a token without `azp` is an
  invalid token (401), on admin and moderation routes too. Backend logs one INFO
  line per hour with the clients seen since the last one,
  `azp counts: {student-personal-cabinet: 12}` (client ids and counts only;
  `(none)` for a token without `azp`), readable with
  `ssh alllexey.dev platform logs <stack>`.
- `id.itmo.azp-mode=log` accepts every client and only counts it. A new client
  (a web sign-in through ITMO.ID, for example) joins the list before it ships.

## Account deletion

- `DELETE /api/users/me` deletes the caller's account with
  `AccountDeletionService` ([runbook](../ops/account-deletion.md)) and returns
  `ApiResponse<String>`. Published links and reviews move to the
  deleted-user placeholder; one `admin_audit` row
  `ACCOUNT_DELETED` records it. Fixture `deleteMyAccount`.
- It takes only a recent sign-in, at most `itmowidgets.account.recent-auth`
  (`ACCOUNT_RECENT_AUTH`, default `10m`) old: a bearer whose ITMO.ID
  `auth_time` claim is that recent, or a web connection approved on the phone
  that recently. Anything older, or a bearer without `auth_time`,
  gets 403 `recent_sign_in_required`; an anonymous request gets 401
  `unauthorized`; a cookie request without `X-Web-Request: 1` gets 403 `csrf`
  first.
- A refreshed access token keeps the `auth_time` of the original sign-in, so a
  client runs a fresh ITMO.ID sign-in (credentials entered again) right before
  the call, and the web a fresh phone approval (`RENEW`). After a 200 the
  client signs out and drops its local data.
- A still-installed 2.1 or 2.2 app that stays signed in recreates an empty
  account at its next device registration, which enrolls (other requests of an
  unknown ISU get 403 `not_enrolled` and create nothing);
  2.3 clients sign out after the deletion. Deletion requests by e-mail or
  Telegram keep using the SQL runbook.
