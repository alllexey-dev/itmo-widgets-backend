# Device pairing and connections

Wire changes follow the [compatibility rule](compatibility.md).

Pairing connects a target device (a self-hosted [agent](agent.md), later a web
version with its own ITMO access) to the account of a phone that is already
signed in, and lets the phone hand the target its ITMO session end-to-end
encrypted. It extends the [phone-approved web login](web.md): one table, one
code space, the same 8-character codes, poll secrets and claim rule. Every route
answers the `ApiResponse<T>` envelope.

## Kinds

| Kind | Target | Key | Claim returns |
|---|---|---|---|
| `WEB_SESSION` | the web cabinet (today's web login) | no | the `iw_session` cookie |
| `AGENT` | a new agent | yes | `sealedBundle` and a new agent token |
| `AGENT_SESSION` | a connected agent that needs a new ITMO session | yes | `sealedBundle` only |
| `WEB_ITMO` | later: web with its own ITMO access | yes | not served yet |

## What Backend sees

The target makes an ephemeral X25519 key pair (`pk`, `sk`) and a 16-byte secret
`s`. `pk` and `s` travel only in the QR or link fragment
`https://<host>/pair#v1.<CODE>.<pk>.<s>`, never in a request to Backend. Backend
gets only the key commitment `SHA-256("iw-pair-commit-v1" || pk || s)` (32
bytes) and returns it in the preview, so the phone can check that the code and
the key came from the same QR. The ITMO refresh token travels only inside the
HPKE ciphertext (`sealedBundle`, RFC 9180 base mode, X25519 / HKDF-SHA256 /
ChaCha20-Poly1305, `enc || ciphertext`), which Backend stores until the single
claim and then deletes. Backend never sees ITMO tokens, `pk`, `s`, `sk` or the
verification code both screens show. The cryptography, the bundle plaintext and
the verification code are specified by the clients (ITMO.Widgets
`vibe/plans/session-handoff-plan.md` section 2.1 until its ADR lands).

Binary values on the wire are base64url without padding: `keyCommitment` is
exactly 32 bytes (43 characters), `sealedBundle` 48 bytes to 16 KiB.

## Flow

1. The target creates a challenge and shows the QR and the link:
   - `POST /api/pair/challenges` (anonymous) with
     `CreatePairingRequest {kind: "AGENT", keyCommitment, name, version?}` →
     `CreatedPairing {id, code, pollSecret, expiresAt}` (the shape of the web
     login's `CreatedChallenge`). `name` 1-60
     characters without control characters, `version` at most 40. Only `AGENT`
     is accepted here; `WEB_SESSION` keeps `POST /api/web/auth/challenges`.
   - `POST /api/agent/pair/session` (agent token, any scope) with
     `{keyCommitment}` → the same; kind `AGENT_SESSION`, name and scopes come
     from the token.
2. The phone opens the code: `POST /api/users/me/pair/{code}/open` →
   `PairingPreview {challengeId, kind, name, version, userAgent, keyCommitment,
   scopes, createdAt, expiresAt}`. `keyCommitment` is null for `WEB_SESSION`;
   `scopes` is set only for `AGENT_SESSION` (the token's). The first account
   that opens a code owns it: anyone else gets 404 `not_found`; the owner may
   open it again. Opening extends the challenge to 10 minutes after the first
   open. `WEB_SESSION` codes open here too.
3. The phone approves `POST /api/users/me/pair/{challengeId}/approve` with
   `ApprovePairingRequest {sealedBundle, scopes}` (both keys present, either
   may be null) → `{}`, or rejects
   `POST /api/users/me/pair/{challengeId}/reject` → `{}`. Only the opener;
   anyone else and a challenge that is not open and pending get 404. A repeated
   approval or rejection by the opener is a no-op. Rules:
   - `sealedBundle` is required for `AGENT` and `AGENT_SESSION`, forbidden for
     `WEB_SESSION`;
   - `scopes` is required (non-empty) for `AGENT` and forbidden otherwise;
   - `AGENT` needs [agent access](agent.md#access-beta) (403
     `permission_denied`) and fewer than 5 active tokens (409
     `business_rule_violation`); both are checked at open and at approval.
   Violations are 400 `invalid_request_data`.
4. The target polls `GET /api/pair/challenges/{id}` (anonymous) with
   `X-Poll-Secret` about every 2 seconds → `PairingPoll {status, sealedBundle,
   agentToken}`:

   | `status` | Meaning |
   |---|---|
   | `PENDING` | not opened yet |
   | `OPENED` | a phone opened it; the target shows the verification code now |
   | `APPROVED` | the one claiming answer: carries `sealedBundle` and, for `AGENT`, `agentToken: CreatedAgentToken {token, info}` |
   | `REJECTED` | the phone said no |
   | `EXPIRED` | timed out, already claimed, or the agent token was revoked |

   Exactly one poll gets `APPROVED`; the claim marks the challenge claimed and
   deletes the bundle in the same transaction, and for `AGENT` it mints the
   token (`createdVia = PAIR`). Every later poll gets `EXPIRED`. `sealedBundle`
   and `agentToken` are null in every other answer. A wrong poll secret is 404,
   like an unknown challenge; a missing `X-Poll-Secret` is 400. A
   `WEB_SESSION` challenge is polled on its old route
   (`/api/web/auth/challenges/{id}`) and gets the cookie there; keyed
   challenges are invisible to the old routes (`/api/web/auth/challenges/**`,
   `/api/users/me/web-login/**`).

Both `/api/users/me/pair/**` routes accept only the app's ITMO.ID bearer: the
cookie is ignored there (the request is anonymous, 401) and agent tokens are
403. The anonymous routes ignore the cookie as well.

### Lifetimes and limits

- A code lives 2 minutes until opened, then 10 minutes after the first open.
  An approval may be claimed up to 1 minute after `expiresAt`. A scheduler
  expires challenges every minute, deletes bundles past that grace and deletes
  rows older than a day.
- Revoking an agent token expires its pending and approved `AGENT_SESSION`
  challenges and deletes their bundles.
- Creation: at most 10 unapproved challenges per client address per 10 minutes,
  shared with web login; `AGENT_SESSION` at most 10 per token per hour.
- Polls: at most 1 per second per challenge. Opens: 30 per user per 10 minutes,
  `not_found` answers included. Approvals and rejections: 20 per user per hour.
  Over a limit is 429 `rate_limited`.

### After a connection

Every claim (pairing of any kind and the old web login) pushes to the owner's
devices after commit:

| Type | Payload | Android expires | iOS alert |
|---|---|---|---|
| `DEVICE_CONNECTED_PAYLOAD` | `connectionId: uuid`, `kind: string` (the pairing kind), `name: string?` (the agent's), `userAgent: string?` (the browser's), `connectedAt: instant` | 12 h after `connectedAt` | `title-loc-key` `notification_device_connected_title`, `loc-key` `notification_device_connected_agent` (`loc-args`: name) or `notification_device_connected_web` (none), collapse `connection-<connectionId>`, thread `connections`, `active` |

`connectionId` is the id in the connections list: the new token's for
`AGENT`, the existing token's for `AGENT_SESSION`, the session's for the web. `kind` may gain values; clients show an unknown kind as
a generic device. The app adds the three loc-keys to its string catalog.
Released apps log and drop the unknown type.

## Connections

The «Подключённые устройства» list: web sessions and agent tokens of the caller.
App bearer or web session (with `X-Web-Request` for the `POST`), so a user who
lost the phone can revoke from the site; agent tokens are 403.

| Route | Data |
|---|---|
| `GET /api/users/me/connections` | `List<ConnectionInfo>`, most recently used first |
| `POST /api/users/me/connections/{id}/revoke` | `{}`; 404 for an unknown, foreign or already ended id |

```
ConnectionInfo {id: uuid, kind: "WEB_SESSION"|"AGENT", name: string|null, userAgent: string|null,
                createdAt: instant, lastUsedAt: instant|null, live: boolean, itmoSession: boolean,
                current: boolean, scopes: AgentScope[]|null}
```

- Web sessions: active ones only; `userAgent` the browser's, `lastUsedAt` the
  last use (written at most every 5 minutes), `current` for the session making
  the request, `live` and `itmoSession` false, `scopes` null.
- Agent tokens: active ones only; `name` the token's, `lastUsedAt` its last
  request or poll, `live` per [liveness](agent.md#events) for any of its
  scopes, `itmoSession` the last `itmo` value of its polls, `scopes` its scopes.
- Revoking ends Backend access at once. It does not end the target's ITMO
  session: an agent wipes it on its next 401. Revoking the current web session
  signs this browser out.

## Storage

V17 (`V17__pairing.sql`) extends `web_login_challenges` with `kind`, `key_commitment`,
`target_name`, `requested_by_version`, `agent_token_id`, `opened_by`,
`opened_at`, `scopes`, `sealed_bundle` and the status `REJECTED`. Logs carry the
challenge id, kind and user id, never the code, poll secret, commitment, bundle
or token.
