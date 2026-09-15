# Schedule and sport privacy

Backend `1.2.0-SNAPSHOT` introduces independent, owner-selected audiences:

- `ALL`: any authenticated caller, even if that caller shares nothing.
- `FRIENDS`: only accepted mutual friends; the default for newly created users.
- `NOBODY`: only the owner. Self reads remain available for every audience.

There is no reciprocal sharing restriction. This repository currently has no block
entity, repository or API. Rejected/cancelled friend requests are not blocks. A future block
model must be checked before granting either `ALL` or `FRIENDS` access.

## Contract

- `GET /api/users/me/privacy` returns `ApiResponse<UserPrivacySettings>`.
- `PUT /api/users/me/privacy` requires both `scheduleVisibility` and
  `sportVisibility`, with exact enum strings, and returns the updated settings.
  Missing, null or unknown values produce HTTP 400 without modifying settings.
- `GET/PUT /api/users/me/settings` has been removed from this unreleased contract.
  There is no boolean settings adapter or fallback.
- `UserData.capabilities` contains `canViewSchedule` and `canViewSport`, computed
  for the authenticated viewer. `/api/users/me/data` returns both self-access
  capabilities as `true`, including when the owner chooses `NOBODY`. Raw audience
  values are returned only by the own-privacy endpoint, never public user data.
- Target schedule reads enforce audience before loading lessons; lesson participant
  lists filter inaccessible owners before mapping their public identity.
- `GET /api/sport/users/{isu}/bookings` returns
  `ApiResponse<UserSportBookingsResponse>` with `lessonIds: List<Long>` of confirmed
  current/upcoming bookings (`lesson.end >= now`). Historical attendance and
  scores are not returned. The updated friendship revision also returns
  `entries: List<SportQueueEntry>` for uncancelled WAITING/NOTIFIED free-sign and
  auto-sign entries from the existing time-bounded queue projections. The wire
  shape is the same as `FriendSportBooking.entry`, including its `type` discriminator.
  Both confirmed and pending data are protected by the same owner capability;
  denied access reads neither source.
- The existing friends-sport feed remains bounded to friends and filters each
  owner's audience. Confirmed sync is independent of visibility so private owners
  can read their own data; queue reconciliation is unchanged.

## Identity, storage and coordinated development

`user_settings.user_id` is both its primary key and a foreign key to `users.id`.
Deleting an isolated user deletes their settings; deleting settings never deletes
that user. The only persisted privacy choices are the required `VARCHAR(16)`
`schedule_visibility` and `sport_visibility` columns. Both SQL and Kotlin default
to `FRIENDS`. There are no nullable legacy choices or duplicate boolean columns.

Registration runs in one short independent transaction. PostgreSQL resolves a
concurrent `INSERT users ON CONFLICT (isu) DO NOTHING`; registration loads the
winning UUID, inserts settings for that UUID if absent, then loads the complete
user. A losing candidate never creates orphan settings. Repeated registration
preserves audiences and auto-sign quotas.

Backend `1.2.0-SNAPSHOT` defines the public user/capabilities and own-privacy DTOs
locally so backend behavior can be verified before updating the shared Core
contract. Core and Android must consume the coordinated `capabilities` shape;
old boolean clients are intentionally not supported by this unreleased contract.
Backend now depends on Core `1.2.0-SNAPSHOT`, published only to MavenLocal for
coordinated development. Android `2.1-SNAPSHOT` consumes the same Core artifact.
This contract requires all three components to update together; local verification
is not a deployment to the running development or production server. A client
using this shape must not be installed against an old boolean-only backend.
The separate legacy `GET /api/app/version` endpoint remains available.

Flyway creates this pre-release schema on a fresh PostgreSQL database; Hibernate
only validates it. Persistence tests use real disposable PostgreSQL. No MariaDB
rows are imported: the planned post-v2.1 reset and cutover require separate
approval, a verified backup, and a successful development rehearsal. See
[database setup and cutover](database.md). No server database mutation, deployment
or library publication is implied by this source change.

## Friendship/profile revision (2026-09-15)

The same unreleased Backend/Core 1.2.0-SNAPSHOT now defines explicit friendships,
public profiles and bounded ISU lookup. See [friendship contract](friendships.md).
Android has not yet been switched to that revision; older locally published Core
snapshots must not be overwritten until Stage 4 adapts the Android repositories.
No server deployment or new MavenLocal publication is implied.
