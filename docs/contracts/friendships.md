# Friendships and public profiles

One row per unordered user pair in `friendships`, with `PENDING` (direction kept
as requester and addressee) or `ACCEPTED`. Accepted rows grant mutual
friendship; access still depends on each owner's audiences
([privacy](privacy.md)). Blocking is not implemented: `BLOCKED` is reserved in
the contract and never produced.

## Routes

All routes require authentication; identity comes from the authenticated UUID.
Responses use the `ApiResponse<T>` envelope.

| Route | Response data |
|---|---|
| `POST /api/friends/{isu}/request` | updated `UserProfile` |
| `POST /api/friends/{isu}/accept` | updated `UserProfile` |
| `POST /api/friends/{isu}/reject` | updated `UserProfile` |
| `POST /api/friends/{isu}/cancel` | updated `UserProfile` |
| `DELETE /api/friends/{isu}` | updated `UserProfile` |
| `GET /api/friends` | `List<UserProfile>` |
| `GET /api/friends/requests/incoming` | `List<UserProfile>` |
| `GET /api/friends/requests/outgoing` | `List<UserProfile>` |
| `GET /api/users/{isu}/friends` | `List<UserProfile>` (accepted only, audience enforced) |
| `GET /api/users/{isu}` | `UserProfile` |
| `POST /api/users/lookup` | `UserLookupResponse` |

Actions have no body. `UserProfile` is exactly `user: UserData` (ISU, name,
picture, groups, viewer capabilities) and `relationship: RelationshipState`
(`NONE`, `OUTGOING`, `INCOMING`, `FRIENDS`, `BLOCKED`) relative to the viewer.
A self profile is `NONE` with all capabilities. Friend lists require
`canViewFriends`; denied requests read no friendships and return 403. Each
listed profile is relative to the authenticated viewer, not the list owner.
Pending requests are never included. Invalid ISUs return 400, unknown owners 404.

Lookup accepts `{"isus":[...]}` with up to 50 positive integers before
deduplication; invalid input is HTTP 400, an empty list returns `{"users":[]}`.
Unknown ISUs are omitted, no account is created, first-occurrence order is kept.
Name search belongs to MyITMO, not Backend, and there is no rate limiter
(Android decision 0006).

## Current study groups on read

`GET /api/friends`, `GET /api/users/{isu}/friends`, `GET /api/users/{isu}` and
`GET /api/users/me/data` resolve groups from the official MyITMO personality
profile’s `education` through the existing typed MyItmoApi client. Every current
education entry is retained; neither the largest course nor a group-name pattern
is used to choose a program. A previously unseen current group can appear without
waiting for another ID-token sync. Known faculty abbreviations are kept; otherwise
the official faculty name is used as the display label.

Authorization and DTO materialization finish before directory I/O. Only group
metadata is cached, never relationships, capabilities, contacts or complete
profiles. The process-local cache holds up to 4096 users for 30 minutes, coalesces
concurrent reads per user and limits each upstream call to 3 seconds. Failed
lookups retry after 30 seconds; transport outages also pause uncached reads for
other users to avoid a timeout per list row. The last verified list, including a
verified empty list, survives an outage. Without a verified snapshot, the existing
ID-token groups remain the fallback. Missing/malformed education is not treated
as an empty list.

Stored `user_groups` and the wire shape remain unchanged. Lookup, request/action
responses, FCM payloads and schedule participant responses are not changed by this
read-path correction.

## Transitions

| Viewer's state | Request | Accept | Reject | Cancel | Remove |
|---|---|---|---|---|---|
| NONE | OUTGOING | 409 | NONE | NONE | NONE |
| OUTGOING | OUTGOING | 409 | 409 | NONE | 409 |
| INCOMING | FRIENDS | FRIENDS | NONE | 409 | 409 |
| FRIENDS | FRIENDS | FRIENDS | 409 | 409 | NONE |

A crossed request accepts immediately. Retries preserve the row; reject, cancel
and remove delete it, and a later request creates a fresh row. Self or
non-positive ISUs are 400, an unregistered participant 404.

Every mutation locks both user rows in ascending ISU order in one transaction
before reading the pair, which also serializes the very first request.
Concurrent same-direction requests stay one `PENDING` row; crossed requests
become one `ACCEPTED` row. The response profile is mapped inside the same
transaction.

## Schema

`V2__friendships.sql` creates `friendships` with requester and addressee foreign
keys, status and timestamp checks, a self-pair check and a unique expression
index on the unordered pair, then converts legacy `friend_requests`: two
reciprocal `ACTIVE` rows become one `ACCEPTED` row whose requester asked first,
a single `ACTIVE` row stays `PENDING`, `CANCELLED` rows are dropped.

## Notifications

Accepted transitions produce `REQUEST_RECEIVED` for the addressee of a new
request and `REQUEST_ACCEPTED` for the original requester; reject, cancel,
remove and idempotent retries produce nothing. Delivery is described in
[notifications](notifications.md).

## Tests

`FriendServiceTest` (transition matrix, retries, self and unknown users, lock
order), `UserControllerTest` (routes, lookup validation, anonymous denial,
removed legacy routes), `FriendshipPersistenceTest` (real concurrency and
capability revocation on PostgreSQL), `PostgreSqlMigrationTest` (V2 conversion
of legacy rows and constraints).
