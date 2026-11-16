# Server statistics

What Backend sees happen and does not store otherwise, counted per day: pairing
steps, pushes, My ITMO calls, friendship changes and HTTP answers. The admin
statistics read them; nothing else does.

## Storage

`stats_daily` (`V31__stat_daily.sql`) holds one row per Europe/Moscow day,
metric and two labels:

| Column | Holds |
|---|---|
| `day` | the Moscow date at the moment of the event |
| `metric` | the metric name, below |
| `dim1`, `dim2` | two labels from code; `dim2` is empty for a metric with one |
| `value` | the count |

Counts add up in memory and are added to their rows (`INSERT ... ON CONFLICT
DO UPDATE SET value = value + excluded.value`) once a minute and on shutdown,
on a thread of their own, so a request never waits for the write and a crash
loses at most the last minute. A failed write keeps the counts for the next
flush. Counts of the current minute appear after the next flush.

A count of an event inside a transaction is taken after the commit; a rolled
back change counts nothing. A day is the Moscow date when the event is counted,
so an event just before midnight that commits after it counts on the next day.

## What is counted

| `metric` | `dim1` | `dim2` | Counted in |
|---|---|---|---|
| `pairing` | `CREATED`, `OPENED`, `APPROVED`, `CLAIMED`, `EXPIRED` | client: `WEB`, `AGENT`, `MCP` | `PairingService` and its expiry job, `CONNECT` pairings only |
| `push` | the FCM `type` | `SENT`, `FAILED`, `TOKEN_GONE`, `NO_DEVICE` | `DeviceService.sendDataMessageToUser` |
| `myitmo` | the gateway operation: `sportTimeSlots`, `sportFilters`, `sportSchedule`, `sportSignLimits`, `personality` | `OK` or a failure kind: `AUTH`, `HTTP`, `NETWORK`, `MAPPING` | `MyItmoApiGateway` |
| `friends` | `REQUESTED`, `ACCEPTED`, `REJECTED`, `CANCELLED`, `REMOVED` | empty | `FriendService` |
| `http` | route group | status class: `2xx`, `3xx`, `4xx`, `5xx` | `HttpCounterFilter` |
| `http_ms` | route group | latency bucket, the upper bound in ms: `10`, `25`, `50`, `100`, `250`, `500`, `1000`, `2500`, `5000`, `inf` | `HttpCounterFilter` |

- **`pairing`**: a step counts once per pairing: opening a code again, a
  repeated approval or a failed claim does not count. `EXPIRED` is a pending
  code past its lifetime, counted by the job that expires it. Renewals of a
  connection are not counted.
- **`push`**: per device: `SENT` means FCM accepted the message (FCM reports
  no delivery to the device), `FAILED` any other error, `TOKEN_GONE` the
  answer `UNREGISTERED`, after which the registration is removed. `NO_DEVICE`
  counts once for a user without a registered device. Without a Firebase key
  (local development) a message is logged instead of sent and still counts as
  `SENT`.
- **`myitmo`**: every call of the technical credential, by its result. A call
  that throws instead (a failed credential write, a bug) is not counted.
- **`friends`**: only a change that happened: a repeated request or the removal
  of a friendship that is not there counts nothing. `ACCEPTED` includes a
  crossed request. Deleting an account removes its friendships without a count.
- **`http`**: every answer under `/api/`, including the 401 and 403 of Spring
  Security and the 413 of the body limit: the filter runs before every other
  filter. An exception that leaves the filter chain counts as `5xx`. The agent
  long-poll (`/api/client/events`, the only asynchronous route) counts once,
  with the status of its answer.
- **`http_ms`**: the same answers but the asynchronous ones: the long-poll
  waits by design. An answer falls into the first bucket whose bound it does
  not exceed.

The **route group** is the first segment after `/api/` of the route pattern
that matched (`/api/users/{isu}` is `users`), never of the raw path. A request
the security chain answered before it reached a controller gets the route the
dispatcher would have matched. No matching route, another method than the
route's, or a pattern whose first segment is not a literal is `other`.

Median and p95 are read from the `http_ms` buckets the way Prometheus'
`histogram_quantile` does: linear interpolation inside the bucket that holds
the rank, the first bucket starting at 0, and a rank in `inf` reading as 5000.
So a value is as precise as its bucket is wide.

## What is never stored

No user, ISU, instance or connection id, IP address, token, user agent,
request path or body, FCM token or message content. The labels are values from
code: enum names, route groups and FCM types; a label outside 1-64 characters
of `[A-Za-z0-9._-]` (`dim2` may be empty) is dropped with a warning that does
not repeat it. The table is not personal data: account deletion does not touch
it.

## Retention

Kept indefinitely; it grows by a few hundred rows a day.

## Adding a counter

The code that owns an event declares its metric once, as a `DailyMetric`
constant next to the enum of its labels (`PairingStep.METRIC`,
`PushOutcome.METRIC`, `MyItmoOperation.METRIC`, `FriendshipAction.METRIC`,
`HttpCounters.HTTP`), takes its `DailyCounter` from
`DailyCounters.counter(metric)` when it is constructed and increments it where
the event happens. A name is 1-32 characters of `[a-z0-9_]` starting with a
letter; a second declaration of the same name fails startup, so two owners
cannot share or collide on a name. Readers pass the same constant to
`DailyCounterReader`. Platform code (`platform/metrics`) knows no feature. A
new metric or label is documented here.

## Admin

The admin statistics read them through `DailyCounterReader` (sums by day and
`dim2`, by `dim1` and `dim2`, and the first day with any counter), each owner
through its own statistics service, and show them in two routes
([admin API](admin.md#statistics)):

| Counters | Read by | Shown in |
|---|---|---|
| `pairing` | `ConnectionStatistics` | `GET /api/admin/stats/features`, `pairing` |
| `push` | `PushStatistics` | `GET /api/admin/stats/server`, `push` and `days[].push*` |
| `myitmo` | `MyItmoStatistics` | `GET /api/admin/stats/server`, `myItmo` and `days[].myItmo*` |
| `http`, `http_ms` | `HttpStatistics` (`platform/metrics`, with `LatencyPercentiles`) | `GET /api/admin/stats/server`, `routes` and `days[]` |

The first day with any counter is `collectedSince` of the server route. The
`friends` counters are collected but not shown yet.
