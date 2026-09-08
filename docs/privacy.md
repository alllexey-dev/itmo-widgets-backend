# Schedule and sport privacy

Backend `1.2.0-SNAPSHOT` introduces independent, owner-selected audiences:

- `ALL`: any authenticated caller, even if that caller shares nothing.
- `FRIENDS`: only mutual active friends; the default for newly created users.
- `NOBODY`: only the owner. Self reads remain available for every audience.

There is no reciprocal sharing restriction. This repository currently has no block
entity, repository or API. Cancelled friend requests are not blocks. A future block
model must be checked before granting either `ALL` or `FRIENDS` access.

## Contract

- `GET /api/users/me/privacy` returns `ApiResponse<UserPrivacySettings>`.
- `PUT /api/users/me/privacy` requires both `scheduleVisibility` and
  `sportVisibility`, with exact enum strings, and returns the updated settings.
  Missing, null or unknown values produce HTTP 400 without modifying settings.
- Existing `/api/users/me/settings` endpoints retain their boolean wire contract.
  Legacy `false` sets `NOBODY`; `true` sets `FRIENDS`, except an existing `ALL`
  remains `ALL` so an old client's full-settings update cannot silently narrow it.
- `UserData.settings` fields are viewer-computed boolean capabilities, not raw
  audiences; `/api/users/me/data` therefore returns `true` for both self-access
  capabilities even with `NOBODY`. Actual choices remain in the own settings
  endpoints; enum settings are exposed only by the own-privacy endpoint.
- Target schedule reads enforce audience before loading lessons; lesson participant
  lists filter inaccessible owners before mapping their public identity.
- `GET /api/sport/users/{isu}/bookings` returns
  `ApiResponse<UserSportBookingsResponse>` with `lessonIds: List<Long>` of confirmed
  current/upcoming bookings (`lesson.end >= now`). Historical attendance and
  scores, waiting queues and predictions are never returned.
- The existing friends-sport feed remains bounded to friends and filters each
  owner's audience. Confirmed sync is independent of visibility so private owners
  can read their own data; queue reconciliation is unchanged.

## Compatibility and schema safety

Two nullable `VARCHAR(16)` columns, `schedule_visibility` and `sport_visibility`,
are added to `user_settings`. Null values resolve from the existing booleans:
`true` becomes `FRIENDS`, `false` becomes `NOBODY`. There is no backfill that widens
existing access. New-user native inserts explicitly write both `FRIENDS` values
and `true` compatibility booleans. Enum writes keep those booleans synchronized.

The server DTOs are initially local to Backend so existing Core 1.1.9 remains
resolvable while the shared client contract is coordinated. New clients must use
the new endpoint rather than send enum fields to the old boolean endpoint; an old
server must fail explicitly rather than silently ignore an `ALL` choice.

The project still uses Hibernate schema update, not Flyway. Local H2 tests exercise
an actual old-schema to new-schema update and verify nullable VARCHAR columns and
preserved legacy rows. This is not a production migration rehearsal: before any
deployment, inspect MariaDB DDL and back up the target database. No deployment,
production connection, database mutation or library publication is implied here.
