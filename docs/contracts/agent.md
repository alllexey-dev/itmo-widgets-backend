# Self-hosted agents

Wire changes follow the [compatibility rule](compatibility.md).

A user may run a small program on their own server (the "agent", the
reference one is `selfhosted/agent` in ITMO.Widgets). It authenticates with a
personal agent token, receives the user's Backend events by long-polling and
acts on them with the user's own ITMO session, which it got through
[pairing](pairing.md). Backend never sees that session: it stores only token
hashes, event payloads it already sends as pushes, and status.

Every route answers the `ApiResponse<T>` envelope. Times are ISO-8601 instants.

## Access (beta)

The feature is on for a user only when both hold:

- the switch `itmowidgets.agent.enabled` (default `true`) is on;
- an admin turned it on for that user: `PUT /api/admin/users/{isu}/agent-access`
  with `{enabled: boolean}` → `AdminAgentAccess {enabled}` (admin only, audited,
  idempotent). `AdminUserDetail` gains `agentAccess: boolean`.

`GET /api/agent/access` (app bearer or web session) → `AgentAccess {enabled}`
tells the app whether to offer agent pairing and manual tokens. With access off:
opening an `AGENT` pairing, `POST /api/agent/tokens` and every agent-token
request answer 403 `permission_denied`; the tokens stay and work again when
access returns. Turning access off never revokes tokens.

## Agent tokens

- Format `iwa_` + 43 base64url characters (32 random bytes). Backend stores only
  the SHA-256 and `hint`, the first 8 characters after the prefix. A token is
  shown once: in the claim answer of an `AGENT` pairing (the normal way, see
  [pairing](pairing.md)) or in the answer of the manual fallback below.
- Sent as `Authorization: Bearer iwa_…`. Such a bearer is never checked as an
  ITMO.ID token and never falls back to the web session cookie.
- At most 5 active tokens per user. No fixed expiry; a token unused for 90 days
  (`itmowidgets.agent.idle-revoke`) is revoked. Revocation is immediate: every
  request resolves the token, nothing is cached. Deleting the account deletes
  its tokens.
- `name`: 1-60 characters, trimmed, no control characters; chosen by the agent
  (pairing) or the user (manual).

### Scopes

| Scope (wire) | Events | Routes |
|---|---|---|
| any | - | `GET /api/agent/me`, `GET /api/agent/events`, `POST /api/agent/pair/session` |
| `sport` | `SPORT_FREE_SIGN_LESSONS_PAYLOAD`, `SPORT_AUTO_SIGN_LESSONS_PAYLOAD` | `GET /api/sport/{auto,free}-sign/entry/my`, `POST /api/sport/{auto,free}-sign/lesson/{lessonId}/{mark-satisfied,cancel}`, `POST /api/sport/sign/sync` |
| `friends:read` | `FRIENDSHIP_EVENT_PAYLOAD` | none |
| `schedule:write` | - | `POST /api/schedule/lessons/sync` |

`AgentScope` is a request enum with the wire values above; a new scope goes into
new fields or routes only. An agent-token request to any other route, or to a
listed route without its scope, is 403 `access_denied` before any controller
runs. That includes managing tokens, connections, pairing approval, web login,
privacy, account deletion, admin and moderation.

### Answers an agent must handle

| Status, code | Meaning | Agent does |
|---|---|---|
| 401 `unauthorized` | token unknown, revoked or idle-expired | wipe the token and the ITMO session, stop; never call ITMO.ID |
| 403 `permission_denied` | access is off for the user or globally | keep everything, retry about hourly |
| 403 `access_denied` | route or scope not allowed | a bug in the agent; do not retry the call |
| 429 `rate_limited` | over the per-token limit | back off at least 10 s |

Rate limits per token, in memory per instance: `GET /api/agent/events` 120 per
minute, every other allowed route 60 per minute.

### Routes

| Route | Auth | Body | Data |
|---|---|---|---|
| `GET /api/agent/me` | agent token | - | `AgentIdentity {isu, tokenId, name, scopes}` |
| `GET /api/agent/events?after=&wait=&itmo=` | agent token | - | `AgentEventsPage`, see [events](#events) |
| `POST /api/agent/pair/session` | agent token | `{keyCommitment}` | `CreatedChallenge`, see [pairing](pairing.md) |
| `GET /api/agent/access` | app bearer or web session | - | `AgentAccess {enabled}` |
| `POST /api/agent/tokens` | app bearer, or web session signed in within 12 h | `CreateAgentTokenRequest {name, scopes}` | `CreatedAgentToken {token, info}` |

`AgentTokenInfo {id: uuid, name, hint, scopes: AgentScope[], createdAt,
createdVia: "PAIR"|"APP"|"WEB"}`. `POST /api/agent/tokens` is the fallback for
an agent set up by hand (`agent login`): `scopes` must be a non-empty set (an
unknown scope name is 400 `invalid_request`); access must be on; a sixth active
token is 409 `business_rule_violation`; more than 10 tokens created in the last
24 hours (pairing included) is 429; an older web session is 403
`recent_sign_in_required` (`itmowidgets.agent.create-recent-auth`, 12 h).
Listing and revoking tokens is part of [connections](pairing.md#connections).

`/me` and `/events` answer 403 `access_denied` to the app's bearer and to web
sessions; `/access` and `/tokens` answer it to agent tokens.

## Events

`GET /api/agent/events?after=<long>&wait=<seconds>&itmo=<ok|none>`

| Parameter | Default | Rule |
|---|---|---|
| `after` | `0` | the last `seq` the agent handled; at least 0 |
| `wait` | `0` | seconds to wait for an event, clamped to 0-30 |
| `itmo` | `none` | `ok` only while the agent holds an ITMO session whose last refresh succeeded |

A negative `after` or another `itmo` value is 400 `invalid_request_data`.

```
AgentEventsPage {events: AgentEvent[], nextCursor: long, hasMore: boolean, gap: boolean}
AgentEvent {seq: long, id: string, type: string, occurredAt: instant, expiresAt: instant, payload: object}
```

- `seq` is the user's own gap-free sequence (1, 2, 3, …), shared by all their
  tokens. `events` holds events with `seq > after` whose type the token's scopes
  allow, oldest first, at most 100; `hasMore` says more are waiting.
- `nextCursor` is the `after` of the next poll. It may be greater than the last
  returned `seq` when events of other scopes were skipped.
- When nothing is there, the request waits up to `wait` seconds and returns as
  soon as an event is appended, or with an empty page at the end. A second poll
  of the same token ends the waiting one with an empty page whose `nextCursor`
  is its own `after`. Clients set an HTTP read timeout of at least `wait + 15` s.
- `gap: true`: events after `after` were dropped by retention, or `after` is
  ahead of the stream (for example after an account was recreated). The page
  then starts at the oldest retained event; the agent resyncs state from REST
  (`entry/my`) and continues from `nextCursor`. It never guesses.
- `type` and `payload` are exactly the `{type, payload}` envelope of the same
  push ([notifications](notifications.md)), nulls omitted, so Core's
  `FcmDecoder` reads them. Agents ignore unknown types. `id` is a stable
  idempotency key (`sport-free:<entryId>:<attempt>`,
  `sport-auto:<entryId>:<attempt>`, `friend:<event>:<actorIsu>:<epoch s>`):
  delivery is at least once, so an agent keeps the ids it handled recently and
  skips repeats. `expiresAt` is the push's deadline, at most 7 days after
  `occurredAt`; acting after it is useless.
- Ack is implicit: the next poll's `after`. Events are kept until `expiresAt`,
  at most 7 days, at most the newest 1000 per user.
- Every poll is a heartbeat: it stores the time (at most once a minute), the
  `itmo` value (at once when it changes) and `after`. A token is **live** for a
  scope while it has the scope and polled within `itmowidgets.agent.liveness`
  (3 min); live for `sport` additionally needs the last `itmo=ok`, and access on.

### Who books a sport place

Backend still reserves attempts exactly as in
[sport automation](sport-automation.md); one executor gets each attempt:

- an owner with a token live for `sport` gets the attempt as an event and **no**
  sport push; otherwise the devices get the push as before;
- an attempt is reserved when the owner has a deliverable device **or** a token
  live for `sport`, so an iPhone without APNs plus a live agent gets attempts;
- an agent with `itmo=none` (session closed, none yet) is not live for `sport`:
  attempts go back to the phone.

The agent books through MyITMO and reports with the existing
`POST /api/sport/{auto,free}-sign/lesson/{lessonId}/{mark-satisfied,cancel}`.
A friendship event goes to the devices as before and, when a token is live for
`friends:read`, also to the event log.

### Agent booked push

When an agent-token `mark-satisfied` call satisfies at least one waiting or
notified entry, Backend pushes to the owner's devices after commit:

| Type | Payload | Android expires | iOS alert |
|---|---|---|---|
| `SPORT_AGENT_BOOKED_PAYLOAD` | `sportLessons: List<SportLessonDto>` (the lesson), `agentName: string` | lesson end | `title-loc-key` `notification_sport_success`, `loc-key` `notification_sport_lesson` (section, start), collapse `sport-booked-<lessonId>`, thread `sport`, `active` |

A repeated `mark-satisfied` changes nothing and sends nothing. Released apps
log and drop the unknown type.

## Settings

Read at startup; `AGENT_ENABLED` is the only environment override, the tracked
Compose file does not forward it (add it through a reviewed override).

| Property | Default | Meaning |
|---|---|---|
| `itmowidgets.agent.enabled` | `true` | the global switch; off answers every agent token 403 `permission_denied` and routes every attempt to devices |
| `itmowidgets.agent.liveness` | `3m` | a token is live while it polled within this; more than a minute |
| `itmowidgets.agent.create-recent-auth` | `12h` | the sign-in age of a web session that may create a token |
| `itmowidgets.agent.max-tokens` | `5` | active tokens per user |
| `itmowidgets.agent.idle-revoke` | `90d` | a token unused this long is revoked |

Several instances: appends and the sequence are safe (row lock), retention is
idempotent. Wake-ups and rate limits are in memory per instance: a poll parked
on one instance answers another instance's append only at its `wait` deadline,
and each instance applies the limits on its own.

## Storage and privacy

`agent_tokens` (V16): owner, name, token hash and hint, scopes, `created_via`,
`last_used_at` (written at most every 5 minutes), `last_poll_at`,
`itmo_session_ok`, `acked_seq`, `revoked_at`. `agent_events` (V16): the
per-user log above. `agent_access` (V16): users an admin let in. Everything is
deleted with the account. Logs carry token ids and user ids, never a token,
payload or name.
