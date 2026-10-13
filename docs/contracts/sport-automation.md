# Sport automation

Wire changes follow the [compatibility rule](compatibility.md).

Backend coordinates forecast (`auto`) and free-place (`free`) queues and tells
devices through FCM when to attempt a booking. A queue entry, a reserved attempt
or an observed free place is never a confirmed university booking; confirmed
lesson IDs come from booking synchronization. Access to other users' sport data
follows [privacy](privacy.md); message shapes are in [notifications](notifications.md).

## Routes

All routes require authentication; identity comes from the authenticated UUID.
Responses use the `ApiResponse<T>` envelope. Entries carry the `type` property
`free` or `auto`; mutations of another owner's entry are rejected.

| Route | Body | Response data |
|---|---|---|
| `POST /api/sport/sign/sync` | `List<Long>`, the caller's confirmed lesson IDs | `String` |
| `GET /api/sport/users/{isu}/bookings` | — | `UserSportBookingsResponse` ([privacy](privacy.md)) |
| `GET /api/sport/friends/sport-bookings` | — | `FriendsSportBookingsResponse {bookings: List<FriendSportBooking>}` |
| `GET /api/sport/auto-sign/limits` | — | `SportAutoSignLimits {limit, available, nextAvailableAt}` |
| `GET /api/sport/auto-sign/entry/my` | — | `List<SportAutoSignEntry>` |
| `POST /api/sport/auto-sign/entry/create` | `SportAutoSignRequest {prototypeLessonId}` | `SportAutoSignEntry` |
| `POST /api/sport/auto-sign/entry/{id}/cancel` | — | `String` |
| `POST /api/sport/auto-sign/lesson/{lessonId}/cancel` | — | `String`; cancels every auto entry matched to that real lesson |
| `POST /api/sport/auto-sign/entry/{id}/mark-satisfied` | — | `String` |
| `POST /api/sport/auto-sign/lesson/{lessonId}/mark-satisfied` | — | `String`; satisfies every auto entry matched to that real lesson |
| `POST /api/sport/auto-sign/queue/current` | — | `List<SportAutoSignQueue>`; a read, kept as `POST` for released clients |
| `GET /api/sport/free-sign/entry/my` | — | `List<SportFreeSignEntry>` |
| `POST /api/sport/free-sign/entry/create` | `SportFreeSignRequest {lessonId, forceSign}` | `SportFreeSignEntry` |
| `POST /api/sport/free-sign/entry/{id}/cancel` | — | `String` |
| `POST /api/sport/free-sign/lesson/{lessonId}/cancel` | — | `String` |
| `POST /api/sport/free-sign/entry/{id}/mark-satisfied` | — | `String` |
| `POST /api/sport/free-sign/lesson/{lessonId}/mark-satisfied` | — | `String` |
| `GET /api/sport/free-sign/queue/current` | — | `List<SportFreeSignQueue>` |
| `GET /api/sport/sections/my` | — | `List<MySportSection>`, the caller's active [sections](#sections) |
| `GET /api/sport/sections/slots?lessonIds=1,2` | — | `List<SportSectionSlot>`, at most 50 IDs; lessons without a provable venue are left out |
| `POST /api/sport/sections/create` | `SportSectionRequest {lessonId}` | `MySportSection`; repeating it returns the active one |
| `POST /api/sport/sections/{id}/occurrence` | `SportSectionOccurrenceRequest {start, skipped}` | `MySportSection` |
| `POST /api/sport/sections/{id}/stop` | — | `String` |

Booking sync replaces the caller's future confirmed bookings with the list and
updates both queue types under the owner lock; it does not depend on any
audience. The friends feed lists confirmed bookings (`entry = null`) and waiting
or notified queue entries of accepted friends whose sport audience admits the
caller; an auto entry appears under its prototype lesson ID. The
`queue/current` lists carry lesson IDs and counts, never owners.

## Ownership, identity, and quota

- Every queue mutation locks the owner's `users` row first with a scalar
  `SELECT id ... FOR UPDATE`, then reloads queue state. Create, cancel, satisfy,
  booking sync, expiry, attempt reservation, and transfer share this ordering.
  Unrelated owners do not share an application-wide lock.
- Scheduler discovery returns entry/owner IDs, not managed entities. An old
  candidate cannot overwrite a subsequently committed cancellation or satisfaction.
- PostgreSQL partial unique indexes allow one **non-cancelled** auto entry per
  `(user_id, prototype_lesson_id)` and one free entry per `(user_id, lesson_id)`.
  They cover every status, including terminal entries, not only `WAITING`.
- Repeated creation returns the existing `WAITING`/`NOTIFIED` entry. Auto checks
  this before quota admission. A terminal predecessor is cancelled and flushed
  before a replacement is inserted; history is retained. Repeating cancellation
  preserves its original timestamp. Foreign-owner mutations are rejected.
- Distinct prototypes may resolve to the same real lesson. Operations by real
  lesson ID satisfy or cancel all matching auto entries, not an arbitrary one.

The auto quota defaults to three. Usage counts non-cancelled `WAITING` entries,
regardless of age, plus entries whose first reserved notification attempt falls
within the last 30 days. An entry meeting both conditions is counted once.
Cancellation before the first attempt releases a waiting slot; cancellation after
an attempt does not erase its rolling-window usage. Admission and insertion run
under the same owner lock, so concurrent requests cannot both spend the last
slot. `nextAvailableAt` is an estimate based on the oldest counted entry, not a
promise that a still-waiting entry will stop counting on that date.

## Frozen forecast and live catalog

Auto creation stores 13 immutable prototype fields: section ID/name/level,
lesson level, type ID, time-slot ID, building ID, teacher ISU/name, room ID/name,
and start/end. The prototype foreign key preserves origin; later catalog or
reference-name changes do not rewrite this snapshot. `targetLesson` retains the
original prototype dates; the predicted occurrence is exactly two weeks later.

The two-week offset is applied exactly once, when the snapshot freezes:
`predicted_starts_at`/`predicted_ends_at` hold the predicted occurrence and no
reader re-derives them. Alongside them the snapshot freezes `match_key`, the
canonical identity of the predicted lesson, produced by `SportQueueRules`. That
object is the only definition of the rule: persistence compares keys and never
restates the predicate, so a rule change cannot leave a query behind. A forecast
whose location proves nothing is stored with a NULL key, which SQL equality
already refuses to match.

A real lesson must match section, teacher, building, room, both levels, type,
time slot, and **both** start and end shifted by two weeks. Labels are retained
for display, not used as identity. Offline matching requires the same positive
raw building ID and positive room ID, independent of filter membership. Explicit
online rooms (`room_id = -1`) match one another when both building IDs are null
or -1; null alone never proves online. Building 0 is a filter category, not a
location. Ambiguous/contradictory locations remain in the catalog and may use
free queues by actual lesson ID, but cannot create or match a forecast. After
binding, `realLessonId` stays fixed. Current metadata is checked again before a
notification: an incompatible catalog change suppresses sending rather than
silently moving the entry to another lesson.

Catalog refresh updates all accepted incoming IDs, including known lessons and
existing dictionary names/time slots. It preserves lesson identity and references
from queues, bookings, and update history. Validation and trimming happen before
a known entity is changed. Invalid rows do not overwrite it; the first valid
occurrence of a duplicated ID wins. `lastSeenAt` advances only for accepted rows.

Missing rows, a partial response, and a valid empty response are **not evidence
of cancellation** and never delete absent catalog lessons. Section/teacher/slot
references need a dictionary entry, but the filters list a new section or
teacher days after its lessons appear. A lesson whose section, teacher or slot
is not in the dictionary adds it from its own `section_name`, `teacher_fio` or
`time_slot_start`/`time_slot_end`; existing entries are never renamed from
lesson fields, and the hourly refresh later replaces the derived name with the
filters' wording. Only a missing reference without a usable name rejects the
lesson. A rejected row is logged as `Sport <kind> [<id>] rejected: <reason>`
(field names, catalog IDs and times, never names or a teacher's ISU) at WARN
once per distinct reason per process and at DEBUG afterwards; the per-run count
is `skipped_lessons`. Building filter options are not a
venue dictionary: raw nullable `sport_lessons.building_id` has no foreign key to
`sport_buildings`, and the frozen `target_building_id` preserves the same nullable
value. Core `SportLessonDto.buildingId` is nullable; the coordinated Android mapper
preserves it. No raw venue is replaced by category 0 or -1 for storage/matching.

Observed on 2026-09-09: the four upstream building filter categories omitted
231 valid lessons on external/other venues, while 41 online lessons used null
building ID and room ID -1. Those rows must be retained, including their original
room names/addresses. The Android filter groups unlisted offline venues under
Other and explicitly online rooms under Online without mutating raw IDs.
Known non-negative
capacity, including zero, is passed to reconciliation. Missing/negative capacity
still permits valid metadata updates but does not invent a queue opportunity.

## Reconciliation, transfer, and ordering

Candidate order is FIFO by `createdAt`, then entry ID. Per-run opportunity
budgets are deliberately different:

| Pass | Policy for one lesson |
|---|---|
| Unresolved forecasts | Reserve at most `max(available - 1, 0)` auto attempts; transfer remaining eligible forecasts to free queues. |
| Already-bound auto entries | Reserve at most `available` eligible attempts. |
| Free queue | Reserve at most one eligible attempt when `available > 0`. |

Ineligible/debounced candidates do not spend an opportunity, and neither does an
owner with no registered device a push can reach and no
[self-hosted agent](agent.md#who-books-a-sport-place) live for `sport`: the entry keeps waiting, its
counters untouched, until such a registration appears. A committed reservation does spend the opportunity
even if delivery then fails. These are scheduling budgets, not reservations in
MyITMO and not an exactly-once or global capacity guarantee across concurrent
scheduler invocations.

Auto-to-free transfer runs in one owner-locked transaction:

| Outcome | Result |
|---|---|
| `CREATED` | Create a non-force free entry; bind and expire the auto entry. |
| `EXISTING` | Keep the active existing free entry; bind and expire auto. |
| `ALREADY_SATISFIED` | Keep the existing booking/satisfied free state; bind and satisfy auto. |
| `LESSON_ENDED` | Create no free entry; bind and expire auto. |
| Obsolete candidate | No change: cancelled, terminal, already-bound, missing, or no longer matching. |

Expected outcomes are values, not exceptions that mark the transaction
rollback-only. A genuine persistence error rolls back that owner's transfer and
is isolated from other candidates and the already-committed catalog. Unresolved
forecasts are revisited for **known** lesson IDs too, including zero capacity,
on subsequent catalog/limit passes; retry does not depend on inserting a new
catalog row. A successful transfer is not recreated on the next pass.

## Short transactions and best-effort delivery

HTTP fetching happens outside database transactions. Catalog data and its
`SUCCESS`/`PARTIAL` statistics commit together before queue processing. A failed
catalog transaction keeps the prior data; a separate transaction attempts to
record `FAILED`. Queue errors cannot retroactively change a catalog result.

Notification processing uses this boundary:

1. Lock the owner and reload current queue state in a short transaction.
2. For auto entries, check stored confirmed bookings before reserving an attempt;
   an existing booking satisfies the entry without consuming an attempt. Transfer
   performs the same booking check. Booking sync also updates both queue types
   under the owner lock, so committed satisfaction wins over an old candidate.
3. Check matching, cancellation, status, deadline, registered devices, debounce,
   and attempt limit. Increment attempts and set first/last-attempt timestamps;
   return an immutable payload intent only after the transaction commits.
4. In a fresh read transaction, recheck that the exact reserved attempt is still
   current. Auto delivery additionally rechecks matching and stored bookings.
   Load immutable device targets, then call FCM with no database transaction open.
   An owner whose agent is live for `sport` gets the attempt as an agent event
   (`sport-<kind>:<entryId>:<attempt>`) instead, and no sport push: one executor
   per attempt.
5. Never merge the old queue entity after FCM. Remove an `UNREGISTERED` device
   only if its ID and token still match, so cleanup cannot delete a replacement
   registration. Diagnostics omit tokens, response payloads, and raw exceptions.

Attempts have a 15-minute minimum interval and default maximum of ten. They count
**committed attempts**, not successful FCM delivery or booking confirmations. An
attempt is reserved only when the owner has at least one registered device a
push can reach, or an agent live for `sport` ([agents](agent.md#who-books-a-sport-place)) (every device but an iOS one with alerts turned off, see
[device registration](notifications.md#device-registration)); a user whose
application never registered, whose devices were all removed as unregistered, or
whose only device is an iPhone with alerts off, cannot exhaust the limit without
ever receiving a message.
The final reservation sets `GAVE_UP_NOTIFYING`; that final intent may still be
sent, but no further attempt is scheduled.

There is no durable outbox and no exactly-once delivery promise. If the process
stops after committing a reservation but before FCM, that attempt can be lost.
Later eligible ticks can retry after debounce while attempts remain; if the lost
reservation was the last attempt, there is no automatic recovery send. Device or
network failures do not roll back spent attempts. Cancellation after the final
eligibility check cannot recall an in-flight send.

## Deadlines and cadence

Academic comparisons use the injected Moscow clock; elapsed update duration uses
a monotonic timer. Eligibility is strict at the deadline:

- Auto: before the frozen prototype end plus two weeks, and before the matched
  real lesson ends.
- Non-force free: before one hour prior to lesson start.
- Force free: before lesson end; force does not extend past the end.

Free creation admits lessons until their end. A non-force entry created after its
notification deadline may therefore be immediately expired during processing.
Preparation checks deadlines itself; correctness does not depend on hourly
cleanup already having run.

Catalog lessons refresh every ten minutes; dictionaries and expiry run hourly.
Limits are checked every minute at second 30: each successful pass first revisits
unresolved forecasts, then alternates bound-auto and free notifications. Debounce,
eligibility, failures, and available capacity may postpone any individual attempt.

## Sections

A section is auto-sign for the auto-sign: a user subscribes to a **slot**, the
same lesson every two weeks, and Backend queues each occurrence for them through
an ordinary auto entry. Limits, forecast matching, transfer to free queues,
delivery, agents and booking sync then apply to that entry unchanged; a section
never books or notifies by itself.

- **Slot.** `SportQueueRules.slotKey` is the forecast match key with the dates
  reduced to the start's phase in the 14-day period and the duration. Two
  lessons share a slot exactly when one is the other's forecast prototype
  shifted by whole periods; Moscow has no DST, so the phase is a weekday, a time
  and a week parity. A lesson without a provable venue has no slot.
- **Subscribing.** Only from a lesson the caller is booked on: a stored booking
  or a satisfied auto/free entry not yet confirmed by sync. One active
  subscription per user and slot (`sport_section_subscriptions`); subscribing
  again from any lesson of the slot returns it.
- **When an occurrence opens.** Auto-sign for an occurrence is possible as soon
  as its prototype, the slot's lesson two weeks earlier, is in the catalog: My
  ITMO publishes about two weeks ahead, so an occurrence opens about four weeks
  before it starts. Subscribing opens the occurrence after the booked lesson at
  once; every catalog refresh then opens the next one when its prototype
  appears, before forecast reconciliation of the same pass.
- **Section queue.** A slot's subscribers are a queue in `(created_at, id)`
  order; the pass handles them in that order, so their entries for one
  occurrence enter the auto-sign queue in subscription order. Each one is
  queued only while their monthly limit has a free slot; otherwise the
  occurrence is recorded as `LIMIT` and not retried, and the subscriber keeps
  their place for the next occurrence. The user's own non-cancelled entry for
  the same prototype is adopted instead of a second one.
- **One row per occurrence.** `sport_section_occurrences` is unique per
  subscription and start: `QUEUED` (with the entry), `LIMIT`, or `SKIPPED`.
  An occurrence is never handled twice.
- **Skipping one date.** `POST .../occurrence` with `skipped = true` turns one
  predicted occurrence off: a queued entry is cancelled, a date not opened yet
  is never queued; the subscription and its place stay. `skipped = false`
  queues an opened date again (limit permitting) or forgets the skip of one not
  opened yet. Only future dates of the section's own slot are accepted.
- **Visibility.** `MySportSection` carries the next three predicted dates with
  their state and entry, the caller's `place` and the slot's `subscribers`.
  `SportSectionSlot` gives a lesson's subscriber count, the cap, `full` and the
  caller's own place. Responses never name or count anything per other user.
- **Cap.** A slot admits `ceil(places x itmowidgets.sport.sections.cap-share)`
  subscribers, at least one when the lesson has places
  (`SPORT_SECTION_CAP_SHARE`, default `0.5`), where places is the lesson's total
  `limit` from the minute limits pass (`sport_lessons.places_limit`). Section
  subscribers are queued weeks before anyone else, so half the places stay for
  students who book or queue one date at a time. A full slot refuses new
  subscribers (`409`, `full = true`); there is no waitlist, a refused user can
  still queue single dates. The cap gates new subscriptions only; while the
  places are unknown there is no cap.
- **Order against manual entries.** `SportAutoQueueOrder` orders one lesson's
  auto queue for attempts and positions. The default is FIFO by entry time, so
  a section entry stands where the moment its occurrence opened puts it; other
  policies are under discussion.
- **Ending.** `stop` ends a subscription; entries already queued stay ordinary
  queues. A slot that published no prototype for two periods after its latest
  opened occurrence (a term ended, the lesson moved) ends with `EXPIRED`.

## Single instance by design

The application is designed to run as exactly one replica. Scheduling is
deliberately not coordinated across processes, and two consequences are load
bearing rather than incidental:

- No scheduled method takes a distributed lock. Two replicas would run every
  cron independently. Per-owner row locks keep each individual transition
  correct, but nothing prevents two processes from reserving two attempts for
  the same entry and delivering two notifications.
- `SportUpdateService.processAutoSignNext` is in-memory. It alternates bound-auto
  and free notification passes minute by minute, so a second process keeps its
  own phase and neither queue gets the intended cadence.

This is a deployment constraint, not a bug: `deploy/compose.yaml` runs a single
named backend container and must not be scaled. Running more than one replica
requires a shared scheduler lock and a persisted alternation phase first.
