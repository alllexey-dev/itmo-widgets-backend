# ISU verification

Backend checks whether the teacher of an own [teacher review](../contracts/teacher-reviews.md)
taught its author: the author R is verified for the teacher T when some ISU
flow F has T in its schedule and R among its members. Users see only
`verified`; a review is never rejected because of ISU. Backend reads ISU pages
with the technical account's `KEYCLOAK_IDENTITY` cookie, the row
`ISU_KEYCLOAK_IDENTITY` of [service credentials](service-credentials.md). The
ISU cache keeps only numbers (flow ids, ISU numbers of teachers and members);
names, groups and photos are never read or stored.

## Session

- **Login.** `GET https://isu.ifmo.ru/pls/apex/f?p=2143:1` with a browser user
  agent, the cookie put into a `CookieManager` for
  `https://id.itmo.ru/auth/realms/itmo/`, and redirects followed by hand (at
  most 10). The APEX session number is the third part of
  `f?p=2143:<page>:<session>` in the final URL.
- **`EXPIRED`.** The final URL is on `id.itmo.ru` under the identity URL, or the
  page contains the Keycloak form `#kc-form-login`: the cookie no longer signs
  in. The row becomes `EXPIRED` and is not tried again until an admin replaces
  it. Another status than 200 or a final URL without a session number is a
  `FAILED` login.
- **Rotation.** When Keycloak issues a new `KEYCLOAK_IDENTITY` during the login,
  it is stored at once with the expiry from its `Max-Age`; a successful login
  sets `OK` and `last_renewed_at`.
- **`SESSION_LOST`.** A page request that ends on the identity URL, on the
  Keycloak form, outside `/pls/apex/` or without a session number lost the
  session. The run logs in again once and repeats the request; a second loss
  fails the request. With a live cookie ISU also silently replaces an unknown
  session number and serves the page; the client then takes the new number from
  the final URL.

## Pages

| Request | Page | Read |
|---|---|---|
| `members/<flow>` | `f?p=2143:GR:<session>::NO::GR_TYPE,GR_DATE,ID_POTOK,ID_DISTP:potok,<dd.MM.yyyy>,<flow>,` with today's date | ISU numbers from the cells `ЧЛВК_ИД` of the table `Список потока`; `span.nodatafound` means no members |
| `teachers/<flow>` | `f?p=2143:15:<session>::NO::SCH,SCH_POTOK_ID,SCH_TYPE,SCH_WEEK,SCH_ID,SCH_FOUND:1,<flow>,5,2,,TRUE` | ISU numbers from the `PID:<isu>` links of the flow schedule |

A page without the expected table, a non-numeric cell or a body over 5 MiB is a
`MAPPING` failure.

**List size.** The members report has no fixed row cap: on 2026-10-12 one
flow listed 287 members on the first page, and APEX pagination
(`pg_min_row=252`) returned its rows 252-287. The client follows pagination
links (`pg_min_row`, at most 20 pages) should ISU render them. APEX keeps the
pagination position of the report region in the session, so the first request
for every flow resets it (`RP` in the URL); otherwise a later flow could start
past its last row and look empty. The flow card's own counter (for example
`256/317`) can differ from the number of listed members; the reason is not
known, so the backend counts the listed members.

## A check

Candidate flows of a review, without repeats and at most 40, in this order:

1. `flow_id` of the author's loaded academic pairs (`lessons` with
   `flow_type_id = 2`) with `teacher_isu = T`, most recently taught first. Other
   lesson kinds do not count: a room booking in My ITMO lists the person who
   booked the room as its teacher;
2. `flowIds` of the author's latest save (`teacher_review_flows`), taken from
   the app's schedule history of the last 8 study periods;
3. the author's schedule flows (`user_subject_flows`), most recently seen first.

For every candidate the flow's teachers are read (cached 30 days); when T is
among them, the members on today's date are read (cached 1 day). If R is a
member, the review becomes `VERIFIED` with this flow (`verified_flow_id`) and
is never checked again. After all candidates without a match it becomes
`UNVERIFIED`; any save by the author makes it `PENDING` and due again.

## Flow links

With link premoderation on, a new pending `FLOW` revision of a
[subject link](../contracts/subject-links.md) is due for the ISU check at once
(`subject_link_revisions.isu_check_due_at`). The check reads the members of the
revision's flow F on today's date (cached 1 day, shared with reviews). When the
author R is among them and F has at most `itmowidgets.isu.flow-link-max-members`
members, the revision is approved: its open `SUBMISSION` case is resolved with
an `APPROVE` decision by `actor=POLICY` and the note
`ISU check: the author is in flow <F> of <n> members`. When R is not a member,
F is larger, or ISU lists no members (an unknown flow), the check ends and the
revision waits for a moderator; it is not checked again. Nothing is approved
when the revision is no longer pending, its case is no longer open (a moderator
decided first) or the link is hidden. `0` turns the approval off.

## Flow link readers

A viewer other than the owner reads a `FLOW` link only when ISU lists them among
the members of its flow (`FlowReaders`, checked after the viewer's own schedule
has that flow). The read uses the same member cache. When the flow has no list
or one older than a day, the read queues the flow in memory, at most once per
30 minutes a flow, and starts a run; a list up to two days old (the purge below
keeps no older one) still answers, a missing one answers no. A run fetches the
queued flows after the reviews and link checks, each once; a failed request is
dropped until the next read, and without a session the whole queue is dropped.

## Queue and runs

Due reviews are `PENDING` rows with `verification_due_at <= now`, oldest first,
in batches of 20. Runs execute one at a time on the single-thread
`isuExecutor`, outside database transactions; a start while a run is going is
dropped, and the run rereads the queue before it ends. After the reviews a run
checks due flow link revisions (`PENDING` with `isu_check_due_at <= now`) the
same way, in batches of 20, then the flows [link readers](#flow-link-readers)
wait for. A run starts after a save of a review or a flow link
commits, when a read queues a flow, after the cookie is replaced, at startup, and every 5 minutes (first
one minute after startup). Between two ISU requests there is a pause of
`itmowidgets.isu.request-delay` (2 s). Every queue update is conditional on the
`verification_due_at` the run read, so a save by the author meanwhile is never
overwritten, and a run checks one due time of a review only once.

Without a session the due reviews and link checks stay `PENDING` and move without counting an
attempt: 6 hours when the cookie is missing or `EXPIRED`, 30 minutes after
another login failure. A failed ISU request moves only its review or link check with an
attempt (`isu_check_attempts` for links), by `min(24 h, 30 min × 2^min(attempts, 6))`, and sets the cookie row
to `FAILED`; after `HTTP` or `NETWORK` the run stops, after `MAPPING` it goes on
with the next review. A cookie in `UNKNOWN` (seeded, copied or replaced) is
checked by a login at the next run even when no review is due.

## Maintenance

Daily at 04:40 Europe/Moscow, unless a run is going (then it is skipped with a
log line):

- member lists older than a day are deleted, then flows that keep neither fresh
  teachers (30 days) nor members;
- when the cookie is present, not `EXPIRED` and `last_renewed_at` is missing or
  older than 7 days, Backend logs in, so the cookie is renewed and never
  expires unused.

## Settings

| Property | Environment override | Default |
|---|---|---|
| `itmowidgets.isu.keycloak-identity` | `ISU_KEYCLOAK_IDENTITY` | empty; a seed only, see [service credentials](service-credentials.md#seeds) |
| `itmowidgets.isu.base-url` | — | `https://isu.ifmo.ru` |
| `itmowidgets.isu.identity-url` | — | `https://id.itmo.ru/auth/realms/itmo/` |
| `itmowidgets.isu.request-delay` | — | `2s` |
| `itmowidgets.isu.connect-timeout` | — | `10s` |
| `itmowidgets.isu.request-timeout` | — | `30s` |
| `itmowidgets.isu.flow-link-max-members` | — | `50`; at most this many flow members for an ISU approval of a flow link, `0` turns it off |

The backend needs outbound HTTPS to `isu.ifmo.ru` and `id.itmo.ru`.

## Admin

The web admin shows the counters of own reviews by state under «Отзывы» →
«Проверка по ИСУ» (`GET /api/admin/reviews/verification`) and the cookie's
status, expiry, last use, last login and last error under «Система» →
«Учётные данные», where an admin also replaces it
([admin API](../contracts/admin.md#system)). Moderators see the state of the
check (`verification`, `verifiedFlowId`) in a review case.

## Logs and errors

`last_error` and log lines carry only a category, an optional HTTP status and
the request: `EXPIRED login`, `HTTP 503 members/93724`,
`NETWORK teachers/93724`, `MAPPING members/93724`,
`SESSION_LOST teachers/93724`. A run that did anything logs one line:

```text
ISU verification checked=… verified=… unverified=… linksApproved=… linksHeld=… postponed=… requests=…
```

A failed login or request logs `ISU login failed: <line>` or
`ISU request failed: <line>` at WARN. Cookie values, session numbers, HTML,
names and review texts never reach the logs or `last_error`.

## Tests

`HttpIsuClientTest` runs the client against MockWebServer with synthetic pages:
login, redirects, expired and lost sessions, silent session replacement, cookie
rotation, both header encodings, pagination links and refusing a foreign host.
`IsuConfigTest` checks the settings and the redacted `toString()`.
`IsuVerificationServiceTest` covers candidates, the caches, verified and
unverified outcomes, postponements, backoff, the cookie statuses and
replacement; `IsuPotokCachePersistenceTest` the cache and its purge;
`TeacherReviewPersistenceTest` that only academic pairs give candidate flows.
`FlowLinkIsuCheckTest` covers flow links: a member of a small flow is approved,
a large flow or a non-member waits, a run without ISU and a failed request
postpone the check until a later success approves, a moderator's decision during
the check wins, and without premoderation ISU is not involved.
`SubjectLinkServiceTest` covers readers: a forged schedule upload reveals no
flow link, an unknown flow is queued once, a day-old list answers and an older
one does not; `IsuVerificationServiceTest` that a queued flow is fetched once and
that without a cookie the queue is dropped.
