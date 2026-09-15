# Friendships and public profiles

## Compatibility

This is the unreleased Backend 1.2.0-SNAPSHOT friendship revision, paired with the
updated Core 1.2.0-SNAPSHOT contract. The version is intentionally unchanged by the
user's 2026-09-15 instruction. Older artifacts with the same snapshot version are
not evidence of compatibility: the client must expose the endpoints below.
Android integration belongs to Stages 4–8 of the friends/public-profile plan and
has not shipped. Do not replace Android's existing local Core artifact before
adapting its repository calls. No MavenLocal/public publication or deployment is
part of Stages 1–3.

## HTTP contract

All routes require authentication. Identity is derived from the authenticated UUID,
never from a supplied requester or viewer ID. Responses use the usual
`ApiResponse<T>` envelope.

| Method and route | Response data |
|---|---|
| `POST /api/friends/{isu}/request` | Updated `UserProfile` |
| `POST /api/friends/{isu}/accept` | Updated `UserProfile` |
| `POST /api/friends/{isu}/reject` | Updated `UserProfile` |
| `POST /api/friends/{isu}/cancel` | Updated `UserProfile` |
| `DELETE /api/friends/{isu}` | Updated `UserProfile` |
| `GET /api/friends` | `List<UserProfile>` |
| `GET /api/friends/requests/incoming` | `List<UserProfile>` |
| `GET /api/friends/requests/outgoing` | `List<UserProfile>` |
| `GET /api/users/{isu}` | `UserProfile` |
| `POST /api/users/lookup` | `UserLookupResponse` |

Friendship actions have no request body. The old `/api/friends/add`, `/remove` and
`/get` routes are removed. Own `/api/users/me/data` and privacy routes are unchanged.

`UserProfile` has exactly `user: UserData` and `relationship: RelationshipState`.
The public identity includes only ISU, name, picture, groups and viewer-scoped
capabilities. Owner audiences and database UUIDs are not returned. A self profile
has relationship `NONE` and both self capabilities. No blocking is implemented;
`BLOCKED` is reserved and never produced by rejection or cancellation.

Lookup accepts `{"isus":[100001,100002]}`, up to 50 positive JSON integers before
deduplication. Null, non-integer, non-positive and oversized input returns HTTP 400.
Empty input returns `{"users":[]}`. Unknown users are omitted, no account is
created, and first-occurrence request order is preserved. Name search belongs to
MyITMO, not Backend; lookup never accepts a URL or imports contact information.


## State transitions

Each unordered user pair has at most one row. PENDING retains requester/addressee
direction. ACCEPTED grants mutual friendship; permissions still depend on the
owner's independent schedule/sport audiences.

| Current relationship of viewer | Request | Accept | Reject | Cancel | Remove |
|---|---|---|---|---|---|
| NONE | OUTGOING | 409 | NONE | NONE | NONE |
| OUTGOING | OUTGOING | 409 | 409 | NONE | 409 |
| INCOMING | FRIENDS | FRIENDS | NONE | 409 | 409 |
| FRIENDS | FRIENDS | FRIENDS | 409 | 409 | NONE |

Retries preserve timestamps and row identity. Cancelling/rejecting/removing deletes
the pair; a later request creates a fresh row. Self/non-positive mutations return
400, an unregistered participant returns 404. In particular the requester cannot
accept their own request or reject on the addressee's behalf.

Mutations lock both existing user rows in ascending ISU order inside one
transaction before looking up the pair. This also serializes the first request
when no friendship row exists. The action response is mapped before these locks
are released. Simultaneous same-direction requests stay one PENDING row; crossed
requests become one ACCEPTED row.

## Schema and verification

`V1__initial_postgresql_schema.sql` is immutable: it has been applied to the
persistent development database (see the 2026-09-09 deployment record) and its
checksum must never be repaired. `V2__friendships.sql` creates `friendships` with
requester/addressee foreign keys, status/timestamp checks, a self-pair check and an
unordered-pair unique expression index, then converts the old `friend_requests`
rows and drops that table:

- two reciprocal `ACTIVE` requests become one `ACCEPTED` row whose requester is
  the earlier request; `responded_at` is the latest activation of either side;
- a single `ACTIVE` request stays `PENDING` in its original direction;
- `CANCELLED` requests carry no relationship and are not copied.

Deploying this revision migrates the development database in place; back it up
first as `docs/database.md` requires. The migration is not automatically reversible.

Reference mechanisms:
[PostgreSQL expression indexes](https://www.postgresql.org/docs/17/indexes-expressional.html),
[Spring Data JPA service transactions](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html).

Tests exercise the transition matrix, retry behavior, authenticated/anonymous
routes, all owner-audience combinations, malformed lookup,
PostgreSQL constraints, real concurrent writes, and immediate capability revocation.
Existing schedule/lesson-participant/sport permission tests use the new accepted
friendship predicate. Backend remains the authorization authority.
