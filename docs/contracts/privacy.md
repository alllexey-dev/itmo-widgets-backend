# Privacy and capabilities

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
- lesson participant lists drop owners the viewer may not see before mapping
  their identity;
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

Registration runs in one short transaction: `INSERT users ON CONFLICT (isu) DO
NOTHING`, load the winning row, insert settings if absent. Repeated registration
preserves audiences and auto-sign quotas.
