# Changelog

## 1.7.0-SNAPSHOT

Paired with Core 1.7.0-SNAPSHOT and Android 2.2-SNAPSHOT.

### 2026-10-02

- Account deletion on request is a manual runbook without an endpoint:
  `docs/ops/account-deletion.sql` deletes an account by ISU in one transaction
  and moves its published subject links and teacher reviews to a per-deletion
  placeholder user (`isu = -<ISU>`, «Удалённый пользователь», reviews
  anonymous); `docs/ops/account-deletion.md` covers confirmation by web
  sign-in, backup, verification and the answer. `AccountDeletionRunbookTest`
  runs the file with `psql` on the real schema and pins every foreign key to
  `users`.
- The sport catalog no longer drops lessons whose section, teacher or time slot
  MyITMO's filters do not list yet: such a lesson adds the missing entry from its
  own `section_name`, `teacher_fio` or `time_slot_start`/`time_slot_end`, and the
  hourly dictionary refresh later replaces the name. From 2026-09-20 to
  2026-10-01 production skipped 15–40 lessons on every ten-minute refresh until
  the filters caught up (sections 99 and 372 and seven teachers). A lesson is
  still rejected when the reference is missing and its name is unusable.
- A rejected catalog row is logged with its reason, e.g.
  `Sport lesson 123 rejected: date_end is not after date (…)`, instead of a bare
  `IllegalArgumentException`: WARN once per distinct reason per process, DEBUG
  afterwards. The log names fields, catalog IDs and times, never names or a
  teacher's ISU.

### 2026-09-29

- AI summaries of teacher reviews through the Gemini API (free tier, model
  `gemini-3.5-flash-lite` on development). `V10__teacher_summaries.sql` adds
  `teacher_summaries`, the one-row `teacher_summary_state` and the
  `GEMINI_API_KEY` row of `service_credentials` (kind `API_KEY`, replaceable by
  an admin, seeded from `GEMINI_API_KEY` only into an empty row). The input is
  the active Reviews copies and published verified own reviews (at most 60 and
  60 000 characters, at least 3); a run at 05:30 Europe/Moscow or by an admin
  rebuilds teachers whose input hash changed, most reviewed first, within a
  daily request budget counted by the Pacific day. Reviews go to the model as
  data inside a randomly labelled block; the answer is checked by Backend's own
  rules, and a rejected answer keeps the previous summary. Only the Gemini
  client uses the new `gemini-proxy` sidecar (Xray 26.2.6) in the Compose
  stack, which forwards only `generativelanguage.googleapis.com`. New settings:
  `AI_SUMMARY_ENABLED`, `GEMINI_MODEL`, `AI_SUMMARY_DAILY_BUDGET`,
  `AI_SUMMARY_REQUEST_DELAY`, `GEMINI_THINKING_BUDGET`,
  `GEMINI_MAX_OUTPUT_TOKENS`, `GEMINI_PROXY_CONTAINER_NAME`. See
  `docs/ops/ai-summaries.md`.
- `TeacherReviewsResponse.summary` (null without a shown summary) on every
  review route and `GET /api/teachers/summary-levels?isu=…` (1–50 teachers) with
  the tone of shown summaries of confidence `MEDIUM` or `HIGH`.
- Admin API: `GET /api/admin/reviews/summaries`, `POST …/summaries/run`,
  `GET …/summaries/teachers`, `PUT …/summaries/{isu}/hidden` and
  `POST …/summaries/{isu}/regenerate`; the audit actions
  `AI_SUMMARIES_RUN_STARTED`, `AI_SUMMARY_HIDDEN`, `AI_SUMMARY_SHOWN` and
  `AI_SUMMARY_REGENERATION_REQUESTED`; the Gemini key in
  `/api/admin/system/credentials`.
- Only academic pairs (`flow_type_id = 2`) decide who teaches:
  `LessonRepository.existsTeacher` (`knownTeacher`) and `findTeacherFlows` (the
  first candidate flows of the ISU check) ignore other lesson kinds, because a
  My ITMO room booking lists the person who booked the room as its teacher.

- `V8__service_credentials.sql`: one `service_credentials` row per secret
  (`MY_ITMO_REFRESH_TOKEN`, `MY_ITMO_ACCESS_TOKEN`, `MY_ITMO_ID_TOKEN`,
  `ISU_KEYCLOAK_IDENTITY`) with status, expiry, last use, renewal, error and
  source. V8 copies the My ITMO tokens from `my_itmo_storage` without changing
  that table; Backend no longer reads or writes it, and it stays with the tokens
  as of V8 so that an image-only rollback keeps working. Follow-up: drop
  `my_itmo_storage` by a separate migration in the next release.
  `ServiceCredentialStore` replaces `MyItmoTokenStore`, `MyItmoStorage` and
  `MyItmoRepository`; seeds from `MY_ITMO_REFRESH_TOKEN` and the new
  `ISU_KEYCLOAK_IDENTITY` are written only into a row without a value. A sport
  catalog `AUTH` failure marks the refresh token `FAILED` (`AUTH sport`). See
  `docs/ops/service-credentials.md`.
- `GET /api/admin/system/credentials` lists the four rows without values;
  `PUT /api/admin/system/credentials/{key}` replaces the refresh token or the
  ISU cookie (admins only), is audited as `SERVICE_CREDENTIAL_REPLACED` with
  target `credential:<key>` and never returns or logs the value.
- `V9__teacher_reviews.sql`: `teacher_reviews`, `teacher_review_revisions`,
  `teacher_review_votes`, `external_teacher_review_votes`,
  `teacher_review_flows` and the ISU flow cache (`isu_potoks`,
  `isu_potok_teachers`, `isu_potok_members`); `external_teacher_reviews.score`,
  `idx_lessons_teacher`, `TEACHER_REVIEW` and the report reasons `OFFENSIVE`,
  `WRONG_TEACHER` in the moderation checks.
- Own teacher reviews: `PUT`/`DELETE /api/teachers/{isu}/reviews/mine`, one
  review per author and teacher, subject up to 200 and text of 30–3000
  characters, anonymous by default. Every content change is a revision that
  waits for a moderator (`TEACHER_REVIEW` premoderation cannot be turned off);
  anonymity applies at once without a revision. `WRITE_REVIEWS`, `VOTE` and
  `REPORT` restrictions and the daily limits apply.
- Votes +1/−1 on own reviews and on the Reviews copies
  (`PUT /api/reviews/{id}/vote`), reports on own reviews
  (`POST /api/reviews/{id}/report`). Copies keep their votes across syncs.
- `GET /api/teachers/{isu}/reviews` changes its response in this snapshot
  cycle: `external` is replaced by `reviews` (other authors' published reviews
  and active copies in one ranked order: score, date, `COMMUNITY` first) with
  `mine`, `canWrite`, `canVote`, `canReport` and `knownTeacher`. Every review
  route answers with this response. See `docs/contracts/teacher-reviews.md`.
- The ISU check of whether the teacher taught the author: a login with the
  `KEYCLOAK_IDENTITY` cookie, flow teachers and members read with jsoup 1.21.1,
  a cache of numbers only (teachers 30 days, members 1 day), a single-thread
  queue with a 2-second pause, postponements instead of rejections, a daily
  keep-alive login. ISU lists at most 250 members per flow, so authors beyond
  them stay unverified. See `docs/ops/isu-verification.md`.
- The web admin gets teacher review cases (`AdminCaseItem.review`,
  `TeacherReviewTarget` with the teacher's name from My ITMO, never stored) and
  `GET /api/admin/reviews/verification` with the counters of the ISU check.

### 2026-09-28

- `GET /api/teachers/{isu}/reviews` returns `TeacherReviewsResponse` with active
  anonymous Reviews copies, ordered newest first; exact dates precede matching
  before-year dates and undated rows follow them. Bearer and web-session reads
  are supported; anonymous callers get 403, nonpositive ISUs get 400
  `invalid_request_data`, malformed ISUs get 400 `invalid_request`, and unknown
  positive ISUs get an empty list. Internal teacher names, upstream ids, raw
  dates and persistence metadata are not exposed. No new migration; the route
  reads the V7 tables. See `docs/contracts/teacher-reviews.md`.

### 2026-09-24
- Teacher reviews are copied from the Reviews project (`reviews.work.gd`) daily
  at 05:00 Europe/Moscow and on an admin's request, only where
  `REVIEWS_SYNC_ENABLED=true` (development). The registry is checked with its
  ETag; a changed registry fetches every teacher with an ISU number (smaller
  Reviews ids are skipped) and applies the complete snapshot at once, marking
  missing reviews with `removed_at`; any failure applies nothing. NUL
  characters are stripped from the copied strings. See
  `docs/ops/reviews-sync.md`.
- `V7__external_teacher_reviews.sql`: `external_teacher_reviews` and
  `external_review_sync_state`. No read API for the app yet.
- `GET`/`POST /api/admin/reviews/sync` (admins only) return `AdminReviewsSync`;
  `POST` starts a run and is 409 `business_rule_violation` when the sync is
  disabled or already running. A start is audited as `REVIEWS_SYNC_STARTED`
  with target `reviews-sync`.
- Saving another student's link to one's own list is removed: chips are ranked
  by score, so a saved link changed nothing. `PUT /api/links/{id}/saved`,
  `SetLinkSavedRequest` and `SubjectLink.isSaved` are gone;
  `V6__drop_subject_link_saves.sql` drops `subject_link_saves`.
- Web login approved from the phone: `POST /api/web/auth/challenges` returns an
  8-character code (2 minutes, single-use, at most 10 unapproved per address in
  10 minutes, then 429 `rate_limited`); the app previews and approves it with
  its bearer token at `GET /api/users/me/web-login/{code}` and
  `POST /api/users/me/web-login/{challengeId}/approve`; the browser polls
  `GET /api/web/auth/challenges/{id}` with `X-Poll-Secret` and exactly one poll
  sets the `iw_session` cookie (`HttpOnly; Secure; SameSite=Strict; Path=/api`,
  2 hours idle, 12 hours at most). `POST /api/web/auth/logout`,
  `GET /api/web/auth/me` and `GET /api/users/me/roles` are added.
- The cookie authenticates every `/api/**` route when no bearer token is sent;
  cookie requests other than GET/HEAD need `X-Web-Request: 1` (403 `csrf`).
- Role `ADMIN` next to `MODERATOR`; the first admin is granted by SQL.
- Admin API for the web admin under `/api/admin/**`: the moderation queue with
  paging and filters, case details, decisions and restrictions (moderators);
  the moderation policy, user search and details, granting and revoking
  `MODERATOR`, the dashboard, sport refresh health, the app version and the
  audit (admins only). Role changes, policy changes and app version changes
  are recorded in `admin_audit`.
- `PUT /api/moderation/settings` is now admin-only and audited; moderators keep
  reading the settings.
- `/api/app/version` and `/api/app/version-info` read `app.latest`,
  `app.minimum` and `app.note` from `app_settings`, falling back to the
  environment; their responses are unchanged.
- `V5__web_sessions_and_admin.sql`: `ADMIN` in the role check,
  `web_login_challenges`, `web_sessions`, `app_settings`, `admin_audit`.
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
