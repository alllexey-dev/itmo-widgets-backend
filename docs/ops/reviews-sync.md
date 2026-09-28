# Reviews sync

Backend keeps a copy of the anonymous teacher reviews of the Reviews project
(`https://onetwozzzplus.github.io/reviews/`, API `https://reviews.work.gd`, no
authentication). Its own moderators have checked the reviews, and it is the
only source of older reviews; Google Sheets are not parsed. The authenticated
[teacher reviews API](../contracts/teacher-reviews.md) serves active copies to
the person's profile in the app.

## Source

- `GET /registry` maps teacher names to ids (`original` and `normalized`). The
  teachers to fetch are the ids of both maps from 100000 up, without repeats;
  such an id is the teacher's ISU number. Smaller ids are Reviews' own teachers
  without ISU: they are never requested or stored.
- `GET /teacher/{id}` returns the name and the reviews; 404 means the teacher
  is gone.
- Every request sends the user agent
  `ITMO.Widgets reviews sync (+https://widgets.alllexey.dev)`. The registry
  request carries the stored ETag in `If-None-Match`; 304 means nothing
  changed.

## Schedule and settings

The sync runs daily at 05:00 Europe/Moscow and when an admin presses
«Синхронизировать». Either way it runs on its own single-thread executor, so a
long run never delays the sport jobs.

| Property | Environment override | Default |
|---|---|---|
| `itmowidgets.reviews-sync.enabled` | `REVIEWS_SYNC_ENABLED` | `false` |
| `itmowidgets.reviews-sync.base-url` | `REVIEWS_SYNC_BASE_URL` | `https://reviews.work.gd` |
| `itmowidgets.reviews-sync.request-delay` | `REVIEWS_SYNC_REQUEST_DELAY` | `1500ms` |
| `itmowidgets.reviews-sync.connect-timeout` | — | `10s` |
| `itmowidgets.reviews-sync.request-timeout` | — | `30s` |

The tracked Compose file forwards only `REVIEWS_SYNC_ENABLED`; it is turned on
only on development. A disabled sync runs neither on schedule nor on request
(409).

## A run

1. The registry with the stored ETag. On 304 the run is `UNCHANGED`: only the
   check time is written, the reviews and totals stay.
2. On 200 every teacher is fetched in turn with `request-delay` between
   requests; about 350 teachers take about 9 minutes. A teacher answering 404
   is left out of the snapshot.
3. The complete snapshot is applied in one transaction: new reviews are added,
   changed text, teacher, subject, source or date is updated, reviews missing
   from the snapshot get `removed_at` (the row stays) and returning ones lose
   it. The new ETag is stored and the run is `UPDATED`.

Strings are trimmed, NUL characters are stripped and blank optional values are
null; a review without text is skipped, a source link is kept only when it is
an http(s) URL, and a review listed under two teachers stays with the first.
`date_raw` is kept as written; `12:18 25.01.2025` also sets `written_on`,
`до 2024` sets `written_before_year`, any other form sets neither.

Any other failure aborts the run without retries: network, HTTP other than
200/404, bad JSON, a body over 5 MiB, a registry without ISU teachers, a
database error. Nothing is applied and the ETag stays, so the next run fetches
the full snapshot again. The run is `FAILED` with a short `last_error` such as
`HTTP 503 /teacher/100123`, `NETWORK /registry`, `MAPPING /registry empty`,
`PERSISTENCE` or `INTERNAL`.

## Lease

One run at a time: a run takes the `running_since` lease in the single
`external_review_sync_state` row with a conditional update. A second start by
the button is 409 `business_rule_violation`; the scheduled one is skipped with
a log line. A lease older than 6 hours counts as stale, and startup clears any
lease because Backend runs as one instance, so a restart frees a lease left by
a crash.

## Admin

The web admin section «Отзывы» (`/app/admin/reviews`, admins only) shows the
state, the stored counts and the «Синхронизировать» button; the API is
[`/api/admin/reviews/sync`](../contracts/admin.md#reviews). Every accepted
manual start is recorded in the audit as `REVIEWS_SYNC_STARTED`.

## Full reload

To fetch every teacher although the registry has not changed, drop the ETag and
start a run with the button (or wait for 05:00):

```sql
UPDATE external_review_sync_state SET etag = NULL WHERE provider = 'REVIEWS_WORK_GD';
```

## Logs

A finished run logs one line:

```text
Reviews sync UPDATED teachers=… reviews=… added=… updated=… removed=… durationMs=…
```

`UNCHANGED` repeats the stored totals with zero changes; a failed run logs
`Reviews sync FAILED <last_error> durationMs=…` at ERROR. Review texts and
response bodies never reach the logs or `last_error`.
