# Sport automation contract

The Backend coordinates forecast (`auto`) and free-place (`free`) queues through
FCM commands. A queue entry, an attempt reservation, and an observed free place
are not confirmations of a university booking. Confirmed lesson IDs are stored
by booking synchronization. Other users' access follows [sport privacy](privacy.md).

This describes the unreleased PostgreSQL implementation, not the state of a
running server. Database setup, technical-log retention, backups, and approved
cutover procedures are in [the database runbook](database.md). These changes do
not deploy the Backend or publish a library.

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
references still require their dictionaries. Building filter options are not a
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

Ineligible/debounced candidates do not spend an opportunity. A committed
reservation does spend it even if delivery fails or no device is registered.
These are scheduling budgets, not reservations in MyITMO and not an exactly-once
or global capacity guarantee across concurrent scheduler invocations.

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
3. Check matching, cancellation, status, deadline, debounce, and attempt limit.
   Increment attempts and set first/last-attempt timestamps; return an immutable
   payload intent only after the transaction commits.
4. In a fresh read transaction, recheck that the exact reserved attempt is still
   current. Auto delivery additionally rechecks matching and stored bookings.
   Load immutable device targets, then call FCM with no database transaction open.
5. Never merge the old queue entity after FCM. Remove an `UNREGISTERED` device
   only if its ID and token still match, so cleanup cannot delete a replacement
   registration. Diagnostics omit tokens, response payloads, and raw exceptions.

Attempts have a 15-minute minimum interval and default maximum of ten. They count
**committed attempts**, not successful FCM delivery or booking confirmations.
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
