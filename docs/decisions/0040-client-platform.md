# 0040 Connections become a platform: a registry of client apps and grants

**Decision (2026-11-27).** Backend's side of ITMO.Widgets ADR 0040 (client
platform), step 1. What a connected client may ask for is no longer hard-coded
per client type: it comes from a registry of client apps the owner keeps in
Backend's database, and the phone hands each client a set of grants the user
chose. Contract: [connected clients](../contracts/connections.md#registry) and
[admin](../contracts/admin.md#connected-client-apps).

**A registry in the database.** `connection_clients` and
`connection_client_grants` (V39) list the owner's client apps and what each may
request: per grant whether it is required, on by default, and a Russian
`purpose`. The migration seeds the three released types (`itmo-widgets-agent`,
`itmo-mcp`, `web`); the owner edits entries through
`/api/admin/connection-clients`, audited like every admin change. Backend
caches the registry in memory and reloads it after every write and every
minute. The kind (`agent`, `mcp`, `web`, `app`) stays on the connection: the
beta switches and the client routes still go by kind.

**Grants.** `widgets:<scope>` is a Widgets scope Backend enforces;
`itmo:refresh:student-personal-cabinet` and `itmo:sso` are ITMO credentials the
phone makes and seals for the client. Backend never sees them: it stores only
the granted ids on the connection, a label it cannot verify. The registry
enforces the policy on every write and every pairing: `itmo:sso` only for a
`self_hosted` client, never an ITMO grant for the web. Texts of ITMO grants and
their warnings are app strings, so Backend cannot soften them; only the
client's `purpose` comes from here.

**Two protocols side by side.** A request in the released shape (`client`,
`scopes`) maps to its seeded entry and the grants the released clients always
got, opens in released apps and gets the v1 bundle byte for byte. A request
with `grants`, or a client of kind `app`, is protocol 2: only an app that opens
with `?protocol=2` sees it, a released app gets 409 `update_required`. The
preview answers the protocol-1 shape unless the app asks for protocol 2.

**Revocation.** Suspending an entry revokes every live connection of it in the
same transaction; the phones then end the ITMO sessions as after any
revocation. `POST /api/users/me/connections/{id}/itmo-closed` records that the
phone took a live client's ITMO access back: the connection stays without ITMO
grants until a renewal.

**Expand only.** The new columns of `connections` and
`connection_pair_challenges` are nullable or defaulted, so the previous image
keeps pairing after a rollback; a row it writes reads as the protocol-1 mapping
of its kind's seeded entry.

**Step 2 (2026-11-28).** Confidential clients, `identity`, same-device return
and grants for any ITMO OIDC client (V40):

- A `confidential` entry keeps Ed25519 public keys in `connection_client_keys`
  and signs every create, renewal and claim with a one-minute client assertion
  (an EdDSA JWS for Backend's audience, its `jti` used once). The phone shows it
  as verified. Ed25519 comes from the JDK alone; no JOSE library was added.
- `identity` is a grant Backend makes: at the claim the client gets a JWT
  Backend signs with its own key (`sub` the ISU, the name, five minutes), and
  checks it against `GET /api/pair/jwks`. The key is optional configuration:
  a server without it answers 409 `identity_unavailable` to a pairing that asks
  for `identity` and works as before otherwise, so production needs no new
  secret to take this release.
- A connection may hold `identity` and no Widgets scope; its token still
  reaches the client routes and nothing scoped.
- `returnUrl` of an entry reaches the phone in `ClientAppInfo`, from the
  registry alone, never from the link.
- `itmo:refresh:<oidcClient>` takes any well-formed ITMO OIDC client: Backend
  keeps no list of them, the app decides which it can mint.

**Not yet.** Self-service registration for other developers.
