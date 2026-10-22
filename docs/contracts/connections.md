# Connected clients

Wire changes follow the [compatibility rule](compatibility.md).

A user connects other clients to their account from the phone: a self-hosted
agent (`agent`, the reference one is `itmo-widgets-agent`), an MCP server
(`mcp`, `itmo-mcp`) or the web version (`web`). Each one is a **connection**:
the client type, its Widgets scopes, its own connection token and, for an agent
or an MCP server, its own ITMO.ID session that the phone signed in for it. The
design is ITMO.Widgets `vibe/plans/connected-clients-plan.md`; this page is
Backend's side of it.

Backend never sees an ITMO token, a cookie, a client key or a pairing secret: it
relays the client's ciphertext once and stores the `sid` of the client's ITMO.ID
session and its end, so the phone can end that session and match it on
`id.itmo.ru/activity`. Every route answers the `ApiResponse<T>` envelope. Times
are ISO-8601 instants.

## Enrollment

`users.enrolled_at` and `users.consent_version` say that the user turned the
services on in the app. Every user from before V25 is enrolled.

| Route | Auth | Body | Data |
|---|---|---|---|
| `PUT /api/users/me/consent` | app bearer | `ConsentRequest {version: int}` (at least 1) | `{}` |

- An ITMO.ID bearer of a user who has not enrolled gets 403 `not_enrolled` on
  every route except `PUT /api/users/me/consent` and
  `POST /api/device/register-device`, which enroll (the registration keeps the
  stored consent version); `/api/app/**` stays reachable anonymously. The first
  bearer request no longer creates a user: only those two routes do.
- Pairing needs an enrolled user (the phone's bearer), so every connection
  starts in the app.

## Clients

| `client` | Scopes by default | Scopes allowed | ITMO session | Live |
|---|---|---|---|---|
| `agent` | `sport` | `sport`, `friends:read`, `schedule:write` | its own | while it long-polls events |
| `mcp` | `reviews:read`, `links:read` | those and `sport:queue` | its own | never |
| `web` | `user` | `user` (fixed) | none | never |

- `agent` needs the beta: the switch `itmowidgets.connections.agent-enabled`
  and an admin's grant, `PUT /api/admin/users/{isu}/agent-access`
  `{enabled}` (audited, idempotent; `AdminUserDetail.agentAccess`). `mcp` needs
  only `itmowidgets.connections.mcp-enabled`. The web is always on.
- At most 5 live connections per user and client type
  (`itmowidgets.connections.max-per-client`).
- With its type off, a connection's every request except `DELETE
  /api/client/self` is 403 `permission_denied`; it works again when the type is
  back. Nothing is revoked.

### Scopes

`ConnectionScope` is a request enum with the wire values below; a new scope goes
into new fields or routes only.

| Scope | Events | Routes |
|---|---|---|
| any | - | [client routes](#client-routes), `GET /api/app/**` |
| `sport` | `SPORT_FREE_SIGN_LESSONS_PAYLOAD`, `SPORT_AUTO_SIGN_LESSONS_PAYLOAD` | `GET /api/sport/{auto,free}-sign/entry/my`, `POST /api/sport/{auto,free}-sign/lesson/{lessonId}/{mark-satisfied,cancel}`, `POST /api/sport/sign/sync` |
| `friends:read` | `FRIENDSHIP_EVENT_PAYLOAD` | `GET /api/friends` |
| `schedule:write` | - | `POST /api/schedule/lessons/sync` |
| `reviews:read` | - | `GET /api/teachers/{isu}/reviews`, `GET /api/teachers/summary-levels` |
| `links:read` | - | `GET /api/subjects/{subjectId}/links` |
| `sport:queue` | - | `GET /api/sport/auto-sign/limits`, `POST /api/sport/{auto,free}-sign/entry/create`, `GET /api/sport/{auto,free}-sign/entry/my`, `POST /api/sport/{auto,free}-sign/entry/{id}/cancel` |
| `user` | - | every route but the client routes of other types and the phone-only routes below; admin and moderation by role |

Phone-only routes take the app's ITMO.ID bearer alone: `/api/users/me/pair/**`,
`PUT /api/users/me/consent`, `PUT /api/users/me/id-token`, `/api/device/**`,
`POST /api/users/me/connections/{id}/itmo-ended`, and widening scopes. Any other
connection request is 403 `access_denied` before a controller runs
(`ConnectionRoutePolicy`).

### The `client_session` rule

An ITMO.ID bearer whose `sid` is a connection's ITMO session (live or revoked,
until the session's `expiresAt` or until the phone reports it ended) gets 403
`client_session`: a client calls Backend only with its connection token, so it
cannot skip its scopes with the ITMO token it already holds. `/api/app/**`
stays reachable anonymously. A client that signs in by itself (env credentials
in `itmo-mcp`) has an unknown `sid` and is the user as before.

## Connection tokens

- Format `iwc_` + 43 base64url characters (32 random bytes), minted at the
  claim of a `CONNECT` pairing. Backend stores only the SHA-256.
- An agent and an MCP server send `Authorization: Bearer iwc_…`; such a bearer
  is never checked as an ITMO.ID token. The web gets the token only as the
  cookie `iw_client=<iwc_…>; Path=/api; Max-Age=400 days; HttpOnly; Secure;
  SameSite=Strict`; the token never reaches JavaScript. The cookie is read only
  on requests without an `Authorization` header.
- Cookie requests other than `GET` and `HEAD` need `X-Web-Request: 1`, else 403
  `csrf`. The pairing routes under `/api/pair/challenges` ignore every
  credential.
- A token is independent of ITMO: it keeps working while the client's ITMO
  session is closed and dies only on revoke. A web connection ends after 30
  days without a request (`itmowidgets.connections.web-idle`); agent and MCP
  tokens have no idle limit. Revocation is immediate.

### Fresh approval for admin routes

`/api/admin/**` and `/api/moderation/**` take a web connection only within 7
days of its last phone approval (`approvedAt`,
`itmowidgets.connections.admin-max-age`), else 401 `reauth_required` before any
service runs. The web then creates a `RENEW` challenge
(`POST /api/client/pair/renew`), the phone approves it, and `approvedAt` moves.
`DELETE /api/users/me` from the web takes an approval within 10 minutes, the
same way (`itmowidgets.account.recent-auth`).

### Answers a client must handle

| Status, code | Meaning | Client does |
|---|---|---|
| 401 `unauthorized` | token unknown or revoked | end its own ITMO session if it can, wipe everything, stop |
| 401 `reauth_required` | web: approval too old for admin routes | show a `RENEW` QR |
| 403 `permission_denied` | the client type is off for the user or globally | keep everything, retry about hourly |
| 403 `access_denied` | route or scope not allowed | a bug in the client; do not retry the call |
| 429 `rate_limited` | over the per-connection limit | back off at least 10 s |

Rate limits per agent or MCP connection, in memory per instance:
`GET /api/client/events` 120 per minute, every other route 60 per minute. The
web has no limit of its own.

## Pairing

The client makes an X25519 key pair (`pk`, `sk`) and a secret `s` and shows
`https://<host>/pair#1.<CODE>.<pk>.<s>` as a QR and a link; `pk` and `s` never
reach Backend. Backend gets the key commitment
`SHA-256("iw-pair-commit-v1" || pk || s)` (32 bytes) and returns it to the
phone, which checks it against the scanned link. Both screens show the SAS
`SHA-256("iw-pair-sas-v1" || challengeId || pk || s) mod 10^6`. The bundle for
an agent or MCP is HPKE (X25519, HKDF-SHA256, ChaCha20-Poly1305) to `pk` with
`info = "itmo-widgets pair v1" || 0x00 || challengeId || kind`; its plaintext is
`PairingBundle {v: 1, challengeId, kind, isu, issuedAt, itmo: {sid, expiresAt,
refreshToken, refreshExpiresAt}}`. Binary values on the wire are base64url
without padding: `keyCommitment` exactly 32 bytes, `sealedBundle` 48 bytes to
16 KiB.

The claim is proven by `claimSecret`, a random secret Backend returns once at
creation and stores as SHA-256. It is never in the QR: whoever sees the QR
knows `s`, so `s` cannot prove that the claimer is the client.

### Routes

| Route | Auth | Body | Data |
|---|---|---|---|
| `POST /api/pair/challenges` | anonymous | `CreateChallengeRequest {kind: "CONNECT", client, keyCommitment, name, version?, platform?, scopes?}` | `CreatedChallenge {challengeId, code, claimSecret, expiresAt}` |
| `POST /api/client/pair/renew` | connection | `RenewChallengeRequest {keyCommitment}` | `CreatedChallenge`, kind `RENEW` |
| `POST /api/users/me/pair/{code}/open` | app bearer | - | `PairingPreview` |
| `POST /api/users/me/pair/{challengeId}/approve` | app bearer | `ApprovePairingRequest {sealedBundle, scopes, itmo: {sid, expiresAt}}`, every key nullable | `ApprovedPairing {connectionId}` |
| `POST /api/pair/challenges/{challengeId}/claim` | anonymous | `ClaimPairingRequest {claimSecret}` | `PairingClaimResponse` |

```
PairingPreview {challengeId, kind: "CONNECT"|"RENEW", client, name, version?, platform?,
                requestedScopes: ConnectionScope[], keyCommitment, connectionId?, expiresAt}
PairingClaimResponse {status: "PENDING"|"OPENED"|"CLAIMED", token?, connection?: ConnectionInfo, sealedBundle?}
```

- Create: `name` 1-60 characters, `version` at most 40, `platform` at most 16
  (`linux`, `macos`, `windows` by convention), trimmed, no control characters.
  `scopes` within the client's allowed set; absent or empty means its
  defaults; the web asks for `user`. Only `CONNECT` is created here.
- Renew: name, version, platform and scopes are the connection's. An agent or
  MCP gets a new ITMO session; the web gets a fresh approval.
- Open: the first account that opens a code owns it; anyone else gets 404, the
  owner may open it again. Opening extends the challenge to 10 minutes after
  the first open. `CONNECT` needs the client type on (403 `permission_denied`)
  and a free slot (409 `business_rule_violation`); `RENEW` only the live
  connection's owner (else 404). Both are checked again at approval.
- Approve: only the opener; a repeated approval returns the same
  `connectionId`. `sealedBundle` and `itmo` are required for `agent` and
  `mcp` and forbidden for `web`; `itmo.sid` 1-64 characters of
  `[A-Za-z0-9._:-]`, `itmo.expiresAt` in the future and within 91 days.
  `scopes` only on `CONNECT`, a non-empty subset of `requestedScopes`; absent
  keeps them all. `CONNECT` creates the connection (its token comes at the
  claim); `RENEW` replaces the connection's `sid` and `expiresAt` and moves
  `approvedAt`. Violations are 400 `invalid_request_data`.
- Claim: about every 2 seconds, at most once a second per challenge (429).
  202 with `PENDING` until opened, `OPENED` until approved; then exactly one
  call gets 200 `CLAIMED`, which marks the challenge claimed and deletes the
  bundle in one statement: `connection`, `token` for a new agent or MCP,
  `sealedBundle` for an agent or MCP. A new web connection gets the token as
  the `iw_client` cookie instead. A wrong claim secret, an expired, already
  claimed or unknown challenge, and the renewal of a revoked connection are
  404 `not_found`.
- Every claim of a `CONNECT` pushes `DEVICE_CONNECTED_PAYLOAD` to the owner's
  devices ([below](#after-a-new-connection)).

### Lifetimes and limits

- A code lives 2 minutes until opened, then 10 minutes after the first open.
  An approval may be claimed up to 1 minute after `expiresAt`. A scheduler
  expires challenges every minute, deletes bundles past that grace and rows
  older than a day.
- An approval nobody claimed is revoked after 15 minutes; it keeps its `sid`
  so the phone can end that session.
- Creation: at most 10 unclaimed challenges per client address per 10 minutes;
  renewals at most 10 per connection per hour. Opens: 30 per user per 10
  minutes, `not_found` answers included. Approvals: 20 per user per hour. Over
  a limit is 429 `rate_limited`.

### After a new connection

| Type | Payload | Android expires | iOS alert |
|---|---|---|---|
| `DEVICE_CONNECTED_PAYLOAD` | `connectionId: uuid`, `client: string`, `name: string`, `connectedAt: instant` | 12 h after `connectedAt` | `title-loc-key` `notification_device_connected_title`, `loc-key` `notification_device_connected_agent` (`loc-args`: name; agent and MCP) or `notification_device_connected_web` (none), collapse `connection-<connectionId>`, thread `connections`, `active` |

`client` may gain values; clients show an unknown one as a generic device.
Released apps log and drop the unknown type.

## Client routes

| Route | Clients | Body | Data |
|---|---|---|---|
| `GET /api/client/me` | any | - | `ClientMe {isu, name, pictureUrl, groups, roles, connection: ConnectionInfo}` |
| `GET /api/client/events?after=&wait=&itmo=` | `agent` | - | `ClientEventsPage`, see [events](#events) |
| `PUT /api/client/status` | `agent`, `mcp` | `ClientStatusRequest {itmo: "ok"\|"closed"\|"none", version?}` | `{}` |
| `POST /api/client/pair/renew` | any | `RenewChallengeRequest {keyCommitment}` | `CreatedChallenge` |
| `DELETE /api/client/self` | any | - | `{}`; clears the `iw_client` cookie |

The app's bearer gets 403 `access_denied` on every client route. `DELETE
/api/client/self` is the client's `unpair` and the web's `Выйти`: the same
revocation as in the app, so a second call is 401. It works with the client type
off. `PUT /api/client/status` stores the ITMO session state at once (an MCP
server reports `closed` on `invalid_grant Session not active`).

## Devices

«Устройства» in the app and the lost-phone path on the web.

| Route | Auth | Body | Data |
|---|---|---|---|
| `GET /api/users/me/devices` | app bearer or web | - | `UserDevices {connections: ConnectionInfo[], installs: InstallInfo[]}` |
| `PUT /api/users/me/connections/{id}/scopes` | app bearer or web | `UpdateScopesRequest {scopes}` | `ConnectionInfo` |
| `POST /api/users/me/connections/{id}/revoke` | app bearer or web | - | `{}` |
| `POST /api/users/me/connections/{id}/itmo-ended` | app bearer | - | `{}` |
| `POST /api/users/me/installs/{instanceId}/sign-out` | app bearer or web | - | `{}` |

```
ConnectionInfo {id, client, name, version?, platform?, scopes: ConnectionScope[], createdAt, approvedAt,
                lastUsedAt?, live: boolean, itmo?: {sid, expiresAt}, itmoStatus: "ok"|"closed"|"none", revokedAt?}
InstallInfo {instanceId, platform: "ANDROID"|"IOS", deviceName?, os?, version, firstSeenAt, lastSeenAt,
             pushEnabled: boolean, sid?, current: boolean}
```

- `connections`: live claimed connections and revoked ones whose ITMO session
  the phone has not ended yet (`revokedAt` set), most recently used first. The
  app hides revoked rows and ends their sessions (below). `lastUsedAt` is the
  last request or poll (written at most every 5 minutes or once a minute);
  `live` per [events](#events); `itmo` null for the web.
- `installs`: the user's app installs from [client statistics](clients.md),
  most recently seen first, joined by install id with the device registration
  that named the same install in `X-Client` (`deviceName`, `pushEnabled`: a
  push token and, on iOS, alerts allowed). `sid` is the install's ITMO.ID
  session from its last `PUT /api/users/me/id-token`; `current` is the calling
  install (`X-Client` `install` of the app's bearer).
- Scopes: narrowing applies at once from the app and the web; widening only
  from the app (the web gets 403 `permission_denied`); always non-empty and
  within the client's allowed set (400). The web's scope is fixed.
- Revoke ends Backend access at once and the connection's pending renewals. It
  does not end the client's ITMO session: the phone ends it with its own
  `profile` token right after, or later, and reports
  `POST /api/users/me/connections/{id}/itmo-ended`, after which Backend forgets
  the `sid` (409 on a connection that is still live). Backend also forgets it at
  `expiresAt`. The client ends its own session on its next 401.
- Install sign-out drops the push registration of another install of the
  caller (400 for the calling install, 404 for an unknown one); the phone ends
  that install's ITMO session itself.
- Unknown and foreign ids are 404 everywhere.

## Events

`GET /api/client/events?after=<long>&wait=<seconds>&itmo=<ok|closed|none>`
(agent only)

| Parameter | Default | Rule |
|---|---|---|
| `after` | `0` | the last `seq` the agent handled; at least 0 |
| `wait` | `0` | seconds to wait for an event, clamped to 0-30 |
| `itmo` | `none` | `ok` only while the agent's ITMO session refreshes; `closed` after `invalid_grant Session not active` |

A negative `after` or another `itmo` value is 400 `invalid_request_data`.

```
ClientEventsPage {events: ClientEvent[], nextCursor: long, hasMore: boolean, gap: boolean}
ClientEvent {seq: long, id: string, type: string, occurredAt: instant, expiresAt: instant, payload: object}
```

- `seq` is the user's own gap-free sequence, shared by all their agents.
  `events` holds events with `seq > after` whose type the agent's scopes allow,
  oldest first, at most 100; `hasMore` says more are waiting. `nextCursor` is
  the `after` of the next poll, past events of other scopes.
- With nothing there the poll waits up to `wait` seconds and returns as soon as
  an event is appended, or with an empty page. A second poll of the same agent
  ends the waiting one with an empty page. Clients set an HTTP read timeout of
  at least `wait + 15` s.
- `gap: true`: events after `after` were dropped by retention or `after` is
  ahead of the stream; the agent resyncs from REST (`entry/my`) and continues
  from `nextCursor`.
- `type` and `payload` are the `{type, payload}` envelope of the same push
  ([notifications](notifications.md)), nulls omitted. `id` is a stable
  idempotency key; delivery is at least once. `expiresAt` is the push's
  deadline, at most 7 days after `occurredAt`. Events are kept until then, at
  most the newest 1000 per user; ack is the next poll's `after`.
- Every poll is a heartbeat: the time (at most once a minute) and `itmo` (at
  once when it changes). An agent is **live** for a scope while it has the
  scope and polled within `itmowidgets.connections.liveness` (3 min); live for
  `sport` additionally needs the last `itmo=ok` and the beta on. MCP servers
  and the web are never live.

### Who books a sport place

Backend reserves attempts as in [sport automation](sport-automation.md); one
executor gets each attempt:

- an owner with an agent live for `sport` gets the attempt as an event and
  **no** sport push; otherwise the devices get the push as before;
- an attempt is reserved when the owner has a deliverable device **or** an agent
  live for `sport`;
- an agent whose ITMO session is closed or absent is not live for `sport`:
  attempts go back to the phone. An MCP server holding `sport:queue` never
  takes attempts.

The agent books through My ITMO and reports with
`POST /api/sport/{auto,free}-sign/lesson/{lessonId}/{mark-satisfied,cancel}`. A
friendship event goes to the devices as before and, when an agent is live for
`friends:read`, also to the event log.

### Agent booked push

When a connection's `mark-satisfied` call satisfies at least one waiting or
notified entry, Backend pushes to the owner's devices after commit:

| Type | Payload | Android expires | iOS alert |
|---|---|---|---|
| `SPORT_AGENT_BOOKED_PAYLOAD` | `sportLessons: List<SportLessonDto>` (the lesson), `agentName: string` (the connection's name) | lesson end | `title-loc-key` `notification_sport_success`, `loc-key` `notification_sport_lesson` (section, start), collapse `sport-booked-<lessonId>`, thread `sport`, `active` |

## Settings

| Property | Default | Meaning |
|---|---|---|
| `itmowidgets.connections.agent-enabled` (`AGENT_ENABLED`) | `true` | agents for everyone with the beta; off answers agents 403 `permission_denied` and routes every attempt to devices |
| `itmowidgets.connections.mcp-enabled` (`MCP_ENABLED`) | `true` | MCP servers for every enrolled user |
| `itmowidgets.connections.liveness` | `3m` | an agent is live while it polled within this; more than a minute |
| `itmowidgets.connections.max-per-client` | `5` | live connections per user and client type |
| `itmowidgets.connections.web-idle` (`WEB_CONNECTION_IDLE`) | `30d` | a web connection unused this long ends |
| `itmowidgets.connections.admin-max-age` (`WEB_ADMIN_MAX_AGE`) | `7d` | the approval age up to which admin and moderation routes take a web connection |

Several instances: appends and the sequence are safe (row lock), retention is
idempotent. Wake-ups and rate limits are in memory per instance.

## Storage and privacy

- `connections` (V25): owner, client, name, version, platform, scopes, token
  hash, `created_at`, `approved_at`, `last_used_at`, `last_poll_at`,
  `revoked_at`, the client's ITMO session (`itmo_sid`, `itmo_expires_at`,
  `itmo_status`, `itmo_ended_at`). A revoked row keeps `itmo_sid` until the
  phone ends the session or it expires, then goes 90 days after revocation.
- `pair_challenges` (V25): the commitment, the claim secret's hash and the
  ciphertext until the claim; rows go after a day.
- `client_users` (the agent beta grant and the event sequence) and
  `client_events` (the log above), V25.
- `client_instances.itmo_sid` (an install's session) and `devices.install_id`
  (V25), see [client statistics](clients.md).
- Everything is deleted with the account. Logs carry ids, kinds and client
  types only: never a token, claim secret, bundle, commitment, code, name or
  `sid`.
