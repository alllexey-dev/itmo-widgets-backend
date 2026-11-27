# Connected clients

Wire changes follow the [compatibility rule](compatibility.md).

A user connects other clients to their account from the phone: a self-hosted
agent (`agent`, the reference one is `itmo-widgets-agent`), an MCP server
(`mcp`, `itmo-mcp`), the web version (`web`) or another client app the owner
registered (`app`). Each one is a **connection**: the client's
[registry entry](#registry), its Widgets scopes, its own connection token and,
for a client with [ITMO grants](#grants), its own ITMO.ID session that the
phone signed in for it. The design is ITMO.Widgets ADR 0040 (client platform,
[decision 0040](../decisions/0040-client-platform.md) here); this page is
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

A client's **kind** (`client` on the wire) decides the beta switches and the
client routes; its [registry entry](#registry) (`clientId`) decides what it may
request.

| `client` | Registry entry | Scopes by default | Scopes allowed | ITMO grants | Live |
|---|---|---|---|---|---|
| `agent` | `itmo-widgets-agent` | `sport` | `sport`, `friends:read`, `schedule:write` | refresh (required) | while it long-polls events |
| `mcp` | `itmo-mcp` | `reviews:read`, `links:read` | those and `sport:queue` | refresh, `itmo:sso` | never |
| `web` | `web` | `user` | `user` (fixed) | none | never |
| `app` | any later entry | its entry's | its entry's | its entry's | never |

- `agent` needs the beta: the switch `itmowidgets.connections.agent-enabled`
  and an admin's grant, `PUT /api/admin/users/{isu}/agent-access`
  `{enabled}` (audited, idempotent; `AdminUserDetail.agentAccess`). `mcp` needs
  only `itmowidgets.connections.mcp-enabled`. The web and `app` are always on;
  a suspended entry pairs nowhere.
- At most 5 live connections per user and registry entry
  (`maxPerUser` of the entry, else `itmowidgets.connections.max-per-client`).
- With its kind off, a connection's every request except `DELETE
  /api/client/self` is 403 `permission_denied`; it works again when the kind is
  back. Nothing is revoked.
- `client` gained `app` with the registry: a client shows an unknown kind as a
  generic device. No released app meets it until the owner registers such an
  entry.

### Registry

The client apps that may pair, kept by the owner alone in `connection_clients`
and `connection_client_grants` (V39) and edited through the
[admin routes](admin.md#connected-client-apps). Backend caches the registry in
memory, reloads it after every write commits and every minute (another instance
sees a change within a minute). V39 seeds the three released types:

| `id` | `kind` | `name` | `deployment` | Grants (required / by default; purpose) |
|---|---|---|---|---|
| `itmo-widgets-agent` | `agent` | ITMO.Widgets agent | `self_hosted` | `widgets:sport` (no / yes), `widgets:friends:read` (no / no), `widgets:schedule:write` (no / no), `itmo:refresh:student-personal-cabinet` (yes / yes; «чтобы записывать вас на физкультуру») |
| `itmo-mcp` | `mcp` | ITMO MCP | `self_hosted` | `widgets:reviews:read` (no / yes), `widgets:links:read` (no / yes), `widgets:sport:queue` (no / no), `itmo:refresh:student-personal-cabinet` (no / yes; «чтобы ИИ-ассистент видел ваше расписание, зачётку и заявки»), `itmo:sso` (no / no; «чтобы ИИ-ассистент видел баллы БАРС и входил в другие сервисы ИТМО») |
| `web` | `web` | ITMO.Widgets в браузере | `hosted` | `widgets:user` (yes / yes) |

An entry: `id` (a slug, `[a-z0-9][a-z0-9-]{0,63}`), `kind`, `name` (1-60
characters, shown on the phone), `homepage` and `iconUrl` (https, at most 200
characters, or null), `deployment` (`self_hosted`: on the user's own machine for
that user alone; `hosted`: a service holding many users' data), `auth`
(`public`; `confidential` is stored but not accepted at pairing yet), `status`
(`active`, `suspended`), `maxPerUser` (positive or null) and its grants in
order, each with `required`, `requestedByDefault` and `purpose` (the owner's
Russian text of why this client needs it, 1-200 characters, or null; the app
shows it after «Зачем: », so it starts in lower case and does not repeat the
grant's own description).

Every write and every pairing checks the policy: `itmo:sso` only for a
`self_hosted` entry, and a `web` entry has no `itmo:*` grant. A write also needs
known grant ids, each at most once, and at least one `widgets:*` grant; the kind
of an existing entry never changes. Suspending an entry revokes every live
connection of it and ends its pending challenges in the same transaction; the
phones then end their ITMO sessions as after any revocation.

### Grants

A grant is one thing the phone can hand a client. Ids are exact strings:

| Grant | Made by | What the client gets |
|---|---|---|
| `widgets:<scope>` | Backend | the connection token works on that [scope](#scopes): `widgets:sport`, `widgets:friends:read`, `widgets:schedule:write`, `widgets:reviews:read`, `widgets:links:read`, `widgets:sport:queue`, `widgets:user` |
| `itmo:refresh:student-personal-cabinet` | phone | the refresh token of the client's own ITMO.ID session for MyITMO's OIDC client |
| `itmo:sso` | phone | that session's `KEYCLOAK_IDENTITY`: every ITMO service; only for `self_hosted` clients |

Any other `itmo:*` id is unknown in this version. Backend never sees an ITMO
credential: the phone seals the ITMO grants it made into the bundle, and
Backend keeps only the list of their ids on the connection (`itmoGrants`), a
label it cannot verify.

### Scopes

`ConnectionScope` is a request enum with the wire values below; a new scope goes
into new fields or routes only. Requests send scope ids; answers carry them as
[`ScopeInfo`](#scope-texts).

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

### Scope texts

Every answer that lists scopes carries their texts, so a new scope needs no
client release and an older client shows every scope instead of dropping
unknown ones:

```
ScopeInfo {id: ConnectionScope, title: string, description: string}
ScopeGrant {scope: ScopeInfo, granted: boolean}
```

`PairingPreview.requestedScopes` is `ScopeInfo[]`; `ConnectionInfo.scopes` is
`ScopeGrant[]` with every scope the client's registry entry allows, granted or
not, in registry order (then any the connection holds that the entry dropped). Texts follow the request's `Accept-Language`
(`messages/scopes_<lang>.properties`, `ScopeTexts`); only `ru` exists, and it is
the answer for any other or missing language. Clients use `id` only to send a
choice back.

Phone-only routes take the app's ITMO.ID bearer alone: `/api/users/me/pair/**`,
`PUT /api/users/me/consent`, `PUT /api/users/me/id-token`, `/api/device/**`,
`POST /api/users/me/connections/{id}/itmo-ended`,
`POST /api/users/me/connections/{id}/itmo-closed`, and widening scopes. Any other
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
phone, which checks it against the scanned link. There is no code to compare
on both screens: approval on the phone is one tap behind the screen lock, and
the push after a new connection (below) tells every device. The bundle for
an agent or MCP is HPKE (X25519, HKDF-SHA256, ChaCha20-Poly1305) to `pk` with
`info = "itmo-widgets pair v1" || 0x00 || challengeId || kind`; its plaintext is
`PairingBundle {v: 1, challengeId, kind, isu, issuedAt, itmo: {sid, expiresAt,
refreshToken, refreshExpiresAt}}` for [protocol 1](#protocol-2) and the v2
bundle of ITMO.Widgets ADR 0040 (`{v: 2, ..., clientId, itmo: {sid, expiresAt,
items: [{grant, value, expiresAt}]}}`, the granted `itmo:*` ids in request
order) for protocol 2; Backend relays either unread. Binary values on the wire are base64url
without padding: `keyCommitment` exactly 32 bytes, `sealedBundle` 48 bytes to
16 KiB.

The claim is proven by `claimSecret`, a random secret Backend returns once at
creation and stores as SHA-256. It is never in the QR: whoever sees the QR
knows `s`, so `s` cannot prove that the claimer is the client.

### Routes

| Route | Auth | Body | Data |
|---|---|---|---|
| `POST /api/pair/challenges` | anonymous | `CreateChallengeRequest {kind: "CONNECT", client?, clientId?, keyCommitment, name, version?, platform?, scopes?, grants?}` | `CreatedChallenge {challengeId, code, claimSecret, expiresAt}` |
| `POST /api/client/pair/renew` | connection | `RenewChallengeRequest {keyCommitment, grants?}` | `CreatedChallenge`, kind `RENEW` |
| `POST /api/users/me/pair/{code}/open?protocol=` | app bearer | - | `PairingPreview` |
| `POST /api/users/me/pair/{challengeId}/approve` | app bearer | `ApprovePairingRequest {sealedBundle, scopes, itmo: {sid, expiresAt}, granted?}`, every key nullable | `ApprovedPairing {connectionId}` |
| `POST /api/pair/challenges/{challengeId}/claim` | anonymous | `ClaimPairingRequest {claimSecret}` | `PairingClaimResponse` |

```
GrantRequest {id: string, required?: boolean}
PairingPreview {challengeId, kind: "CONNECT"|"RENEW", client, name, version?, platform?,
                requestedScopes: ScopeInfo[], keyCommitment, connectionId?, expiresAt,
                protocol?: 1|2, app?: ClientAppInfo, grants?: PairingGrant[]}
ClientAppInfo {id, name, homepage: string|null, iconUrl: string|null, deployment: "self_hosted"|"hosted", verified: boolean}
PairingGrant {id, required: boolean, title: string|null, description: string|null, purpose: string|null, granted: boolean|null}
PairingClaimResponse {status: "PENDING"|"OPENED"|"CLAIMED", token?, connection?: ConnectionInfo, sealedBundle?}
```

- Create: `name` 1-60 characters, `version` at most 40, `platform` at most 16
  (`linux`, `macos`, `windows` by convention), trimmed, no control characters.
  Exactly one of `client` (a kind: its seeded registry entry; `app` has none)
  and `clientId` (an active, `public` registry entry; unknown is 400, suspended
  403 `permission_denied`). `scopes` and `grants` exclude each other. Without
  `grants`: `scopes` within the entry's `widgets:*` grants, absent or empty
  means those on by default (the web asks for `user`), plus for an agent or
  MCP server `itmo:refresh:student-personal-cabinet` as required. With
  `grants`: see [protocol 2](#protocol-2). At least one `widgets:*` grant is
  requested. Only `CONNECT` is created here.
- Renew: name, version, platform and scopes are the connection's. Without
  `grants`, an agent or MCP server asks for the refresh grant (protocol 1: the
  v1 bundle carries only the refresh token) and the web for no ITMO grant; a
  renewal with `grants` asks for those ITMO grants. A client that gets ITMO
  grants gets a new ITMO session; every renewal moves the approval.
- Open: the first account that opens a code owns it; anyone else gets 404, the
  owner may open it again. `protocol` is the highest pairing protocol the app
  knows: a protocol-2 challenge opened without `protocol=2` is 409
  `update_required` and stays unopened; a protocol-1 challenge answers the
  protocol-1 preview byte for byte without it, and with `protocol=2` also
  `protocol`, `app` and `grants` ([below](#protocol-2)). Opening extends the
  challenge to 10 minutes after the first open. `CONNECT` needs the client kind
  on and its entry active (403 `permission_denied`) and a free slot of the
  entry (409 `business_rule_violation`); `RENEW` only the live connection's
  owner (else 404). Both are checked again at approval.
- Approve: only the opener; a repeated approval returns the same
  `connectionId`. Protocol 1: `granted` null; `sealedBundle` and `itmo` are
  required for `agent` and `mcp` and forbidden for `web`; `scopes` only on
  `CONNECT`, a non-empty subset of `requestedScopes`, absent keeps them all.
  Protocol 2: see [below](#protocol-2). `itmo.sid` 1-64 characters of
  `[A-Za-z0-9._:-]`, `itmo.expiresAt` in the future and within 91 days.
  `CONNECT` creates the connection (its token comes at the claim) with its
  `itmoGrants`; `RENEW` moves `approvedAt` and, when ITMO grants were sealed,
  replaces the connection's `sid`, `expiresAt` and `itmoGrants`. Violations
  are 400 `invalid_request_data`.
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

### Protocol 2

A challenge is protocol 2 when its request sent `grants` or names an entry of
kind `app`; every other challenge is protocol 1, as the released clients and
apps pair. Released apps open without `?protocol=2` and get 409
`update_required` for a protocol-2 challenge; the app that knows grants always
opens with `?protocol=2`.

- `grants` on create: each id a grant of the entry, at most once (400
  otherwise). `required` null keeps the entry's; `true` makes an optional grant
  required; it never drops a required one, and every required grant of the
  entry missing from the list is added. Stored in registry order.
- `grants` on renew: only `itmo:*` ids of the entry (400 otherwise): the ITMO
  grants the client wants after the renewal. A renewal of an `app` connection
  without `grants` asks for its current `itmoGrants`, or the refresh grant when
  it has none.
- Preview with `?protocol=2`: `protocol`; `app` (`verified` is false while only
  public clients pair); `grants`, the requested ones in registry order.
  `title` and `description` are the [scope texts](#scope-texts) of a
  `widgets:*` grant and null for `itmo:*` (the app owns those texts and their
  warnings); `purpose` comes from the registry; `granted` only on a `RENEW` and
  only for an ITMO grant: whether the connection already holds it, else null.
  `client` stays the kind and `requestedScopes` the `widgets:*` part.
- Approve: `granted` (the grant ids the user chose) is required and `scopes`
  null. `granted` is a subset of the requested ids holding every required one
  and, for `CONNECT`, at least one `widgets:*` grant. `sealedBundle` and
  `itmo` are required iff `granted` has an `itmo:*` id, forbidden otherwise.
  The connection's scopes are the `widgets:*` part (`CONNECT` only), its
  `itmoGrants` the `itmo:*` part. A `RENEW` approved without an ITMO grant only
  moves `approvedAt`: the client keeps the session and grants it had.

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
| `POST /api/users/me/connections/{id}/itmo-closed` | app bearer | - | `{}` |
| `POST /api/users/me/installs/{instanceId}/sign-out` | app bearer or web | - | `{}` |

```
ConnectionInfo {id, client, name, version?, platform?, scopes: ScopeGrant[], createdAt, approvedAt,
                lastUsedAt?, live: boolean, itmo?: {sid, expiresAt}, itmoStatus: "ok"|"closed"|"none", revokedAt?,
                clientId: string, app: ClientAppInfo, itmoGrants: string[]}
InstallInfo {instanceId, platform: "ANDROID"|"IOS", deviceName?, os?, version, firstSeenAt, lastSeenAt,
             pushEnabled: boolean, signedOutAt?, sid?, current: boolean}
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
  install (`X-Client` `install` of the app's bearer). `signedOutAt` is when
  install sign-out (below) last dropped this install's push registration; null
  for an install that never lost one this way, and cleared when the install
  registers a push token again (`POST /api/device/register-device` naming it
  in `X-Client`).
  So `pushEnabled: false` with `signedOutAt` is "notifications were turned off
  from another device", without it "never turned on".
- Scopes: narrowing applies at once from the app and the web; widening only
  from the app (the web gets 403 `permission_denied`); always non-empty and
  within the client's allowed set (400). The web's scope is fixed.
- Revoke ends Backend access at once and the connection's pending renewals. It
  does not end the client's ITMO session: the phone ends it with its own
  `profile` token right after, or later, and reports
  `POST /api/users/me/connections/{id}/itmo-ended`, after which Backend forgets
  the `sid` (409 on a connection that is still live). Backend also forgets it at
  `expiresAt`. The client ends its own session on its next 401.
- «Забрать доступ к ИТМО»: the phone ends a live connection's ITMO session
  itself and reports `POST /api/users/me/connections/{id}/itmo-closed`; Backend
  sets `itmoStatus` to `closed`, empties `itmoGrants` and forgets the `sid` and
  `expiresAt`. The connection and its scopes stay; a renewal brings ITMO grants
  back. 404 for an unknown or another user's connection, 409 for a revoked one;
  repeating it is a no-op.
- Install sign-out drops the push registration of another install of the
  caller and sets its `signedOutAt` (400 for the calling install, 404 for an
  unknown one); for an install that has no registration it changes nothing.
  The phone ends that install's ITMO session itself. It does not
  hold against that phone: when its app registers again (on sign-in and on
  enabling services), the registration and the pushes are back and
  `signedOutAt` is null.
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
| `itmowidgets.connections.max-per-client` | `5` | live connections per user and registry entry without its own `maxPerUser` |
| `itmowidgets.connections.web-idle` (`WEB_CONNECTION_IDLE`) | `30d` | a web connection unused this long ends |
| `itmowidgets.connections.admin-max-age` (`WEB_ADMIN_MAX_AGE`) | `7d` | the approval age up to which admin and moderation routes take a web connection |

Several instances: appends and the sequence are safe (row lock), retention is
idempotent. Wake-ups and rate limits are in memory per instance; the registry
cache reloads every minute, at once on the instance that wrote it.

## Storage and privacy

- `connection_clients` and `connection_client_grants` (V39): the
  [registry](#registry); no user data.
- `connections` (V25): owner, client kind, name, version, platform, scopes,
  token hash, `created_at`, `approved_at`, `last_used_at`, `last_poll_at`,
  `revoked_at`, the client's ITMO session (`itmo_sid`, `itmo_expires_at`,
  `itmo_status`, `itmo_ended_at`); since V39 its registry entry (`client_id`,
  null only on a row the image before V39 wrote, read as the kind's seeded
  entry) and the ITMO grant ids sealed for the session (`itmo_grants`, never a
  credential). A revoked row keeps `itmo_sid` until the phone ends the session
  or it expires, then goes 90 days after revocation.
- `connection_pair_challenges` (V25): the commitment, the claim secret's hash and the
  ciphertext until the claim; since V39 the registry entry (`client_id`), the
  requested grants (`requested_grants`, `[{id, required}]`, null on the previous
  image's rows: the protocol-1 mapping) and `protocol` (1 or 2). Rows go after
  a day.
- `connection_users` (the agent beta grant and the event sequence) and
  `connection_events` (the log above), V25.
- `client_installs.itmo_sid` (an install's session) and `push_devices.install_id`
  (V25), see [client statistics](clients.md).
- Everything is deleted with the account. Logs carry ids, kinds and client
  types only: never a token, claim secret, bundle, commitment, code, name or
  `sid`.
