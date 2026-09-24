# Changelog

## 1.7.0-SNAPSHOT

Paired with Core 1.7.0-SNAPSHOT and Android 2.2-SNAPSHOT.

### 2026-09-24
- A shared link goes to one schedule flow of any depth instead of the fixed
  group and lecture audiences: visibility is `PRIVATE`, `FLOW` or `ALL`, and
  `FLOW` requires a `flowId` among the author's flows of the subject and period
  (otherwise 400 `audience_unavailable`). A viewer sees a `FLOW` link only when
  that exact flow is in their schedule, so a link for `ФИЗ ПИИКТ 3.2.1` stays
  with that lab group and one for `ФИЗ ПИИКТ 3` reaches the whole lecture flow.
- `flowId` is added to `SaveSubjectLinkRequest`, `SubjectLink` and
  `SubjectLinkRevision`; `audienceLabel` is the flow's schedule name.
  `LinkAudience` is now `flowId`, `label`, `typeId`, `depth`, one per flow,
  sorted by depth and then name.
- `FlowMembership.isMember` replaces `sharesAny`; `SubjectFlow` carries `typeId`
  and `depth`. Revisions store the flow, and `subject_link_audience` is gone.
  `V4__subject_links.sql` is edited in place; development link tables are
  recreated.

### 2026-09-23
- Subject links replace the first subject resource iteration, which ran only on
  development as 1.3.0-SNAPSHOT (personal copies, submission versions,
  selections). A link has a category (`SCORES` … `CHAT`, `OTHER`) and a
  visibility: `PRIVATE`, `GROUP`, `FLOW` or `ALL`.
- `GROUP` and `FLOW` reach the author's practice or lecture flows of that
  subject and period. Schedule sync records the flows in `user_subject_flows`;
  `FlowMembership` trusts the uploaded schedule, and intakes with the same group
  name never share links. No flow means 400 `audience_unavailable`.
- Every non-private change is an immutable revision; others see the latest
  approved one. Group and flow links, and public links without premoderation,
  are approved by the policy at once; public links under premoderation wait for
  a moderator, and an edit keeps the old content visible until the decision.
- Lists collapse duplicate URLs, rank group and flow links first, and offer up
  to 10 approved public links of earlier periods for materials, tasks,
  recordings, notes and exam. Votes are -1/0/+1; saving others' links and one
  pin per subject and period are only possible for visible links.
- Routes: `GET /api/subjects/{subjectId}/links`, `PUT`/`DELETE /api/links/{id}`,
  `PUT /api/links/{id}/saved`, `PUT /api/subjects/{subjectId}/links/pin`,
  `PUT /api/links/{id}/vote`, `POST /api/links/{id}/report`. The moderation
  case target is `SubjectLinkTarget`. `GET /api/users/me/resources` is gone.
- Any HTTPS host is accepted; internationalized hosts become Punycode.
  The default daily submission limit is 20 revisions.
- Moderation: reusable cases, immutable decisions (moderator or `POLICY`
  actor), moderator roles, capability restrictions and typed key/value policies
  with a live premoderation switch.
- A single rewritten `V4__subject_links.sql` replaces the earlier V4 and V5;
  development resource tables are recreated rather than migrated.

## 1.2.1 — 2026-09-21

### 2026-09-21
- Every response that carries a user profile now resolves current study groups
  from the MyITMO directory: `GET /api/schedule/lessons/{pairId}/friends`,
  `POST /api/users/lookup`, `GET /api/friends/requests/incoming` and
  `/outgoing`, and the five friendship action responses join the four reads
  decorated since 2026-09-17. Before, a friend on a lesson could show last
  year's group because the ID token lists every group a student ever had.
- When the directory is unavailable, stored groups are returned highest course
  first and then by name, instead of the set's arbitrary order.
- No schema or wire change; clients need nothing.

## 1.2.0 — 2026-09-21

### 2026-09-21
- Production moved to this version on PostgreSQL 17 (fresh cluster, no
  MariaDB import); see `docs/ops/deployments.md`.
- Version `1.2.0` on Core `1.2.0` from Maven Central (no more Maven Local
  snapshot). No schema or API change; 443 tests green.

### 2026-09-20
- `GET /api/schedule/lessons/{pairId}/friends?date=YYYY-MM-DD` lists the
  viewer's accepted friends on one lesson occurrence, filtered by each friend's
  schedule audience. `GET /api/schedule/lessons/{pairId}/users`, which listed
  every visible attendee without checking that the caller attends, is removed.

### 2026-09-17
- Friend-list and profile reads now use current MyITMO education rather than
  historical ID-token groups. Parallel programs are preserved, viewer access is
  unchanged, and bounded caching retains known data during upstream outages.

### 2026-09-17
- `UserData.name` is empty, not `Нет данных`, while the owner's identity is
  still unpublished; clients render the placeholder.

### 2026-09-16
- Viewer-scoped friends lists with independent ALL / FRIENDS / NOBODY privacy;
  V3 defaults friends visibility to ALL for existing and new accounts.
- Sport queues no longer reserve notification attempts for owners without a
  registered device; such entries keep waiting instead of reaching
  `GAVE_UP_NOTIFYING` unheard.
- Friendship notifications: `FriendService` returns notification intents,
  `UserProfileService.act` delivers them after commit on a dedicated executor,
  the payload is rechecked against the current relationship. FCM messages carry
  `recipient_isu` next to the `data` envelope.

### 2026-09-15
- Explicit friendships: one row per pair with `PENDING`/`ACCEPTED`, routes
  `POST /api/friends/{isu}/request|accept|reject|cancel`, `DELETE /api/friends/{isu}`,
  `GET /api/friends`, `GET /api/users/{isu}`, `POST /api/users/lookup`.
  `V2__friendships.sql` converts legacy `friend_requests`.
- `GET /api/sport/users/{isu}/bookings` also returns pending queue entries.
- Development database recreated on V1 + V2; V1 declared immutable.

### 2026-09-09 — 2026-09-14
- PostgreSQL 17 with Flyway and Hibernate validation; Testcontainers suites for
  migrations, native mutations, startup and concurrency.
- Sport automation hardened: owner row locks, frozen forecast with match key,
  short transactions, after-commit best-effort delivery, safe refresh outcomes,
  technical log retention, online and external venues.
- Independent privacy audiences (`ALL`/`FRIENDS`/`NOBODY`) and viewer-scoped
  capabilities; `GET /api/app/version-info`.

## 1.1.6
- Last MariaDB release with reciprocal friend requests and boolean privacy.
