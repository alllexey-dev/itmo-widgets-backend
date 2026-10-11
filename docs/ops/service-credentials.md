# Service credentials

Backend keeps the secrets of external services in one table,
`service_credentials` (`V8__service_credentials.sql`): the technical My ITMO
account's tokens, used for the sport catalog and the teacher names of the web
admin, the ISU `KEYCLOAK_IDENTITY` cookie of the
[ISU verification](isu-verification.md) and the Gemini API key of the
[AI summaries](ai-summaries.md). `ServiceCredentialStore` is the only
reader and writer. An admin sees everything about a value except the value
itself and replaces values through one audited admin API.

## Table

One row per secret; the key is the name of `ServiceCredential`. V8 creates the
first four rows and V10 (`V10__teacher_summaries.sql`) the `GEMINI_API_KEY`
row, so a row always exists.

| Key | Kind | Replaceable by an admin | Seed variable (property) | «Expires soon» window |
|---|---|---|---|---|
| `MY_ITMO_REFRESH_TOKEN` | `REFRESH_TOKEN` | yes | `MY_ITMO_REFRESH_TOKEN` (`itmowidgets.my-itmo.refresh-token`) | 1 day |
| `MY_ITMO_ACCESS_TOKEN` | `ACCESS_TOKEN` | no | — | — |
| `MY_ITMO_ID_TOKEN` | `ID_TOKEN` | no | — | — |
| `ISU_KEYCLOAK_IDENTITY` | `COOKIE` | yes | `ISU_KEYCLOAK_IDENTITY` (`itmowidgets.isu.keycloak-identity`) | 14 days |
| `GEMINI_API_KEY` | `API_KEY` | yes | `GEMINI_API_KEY` (`itmowidgets.ai-summary.api-key`) | — |

| Column | Meaning |
|---|---|
| `key`, `value` | the name and the secret itself (plain text); `value IS NULL` exactly when `status = 'MISSING'` |
| `expires_at` | when the value expires, if known |
| `status` | `MISSING` (no value), `UNKNOWN` (copied, seeded or replaced and not used yet), `OK`, `EXPIRED`, `FAILED` |
| `last_used_at` | the last successful use |
| `last_renewed_at` | the last renewal at the issuing service: a My ITMO token refresh or an ISU login |
| `last_error_at`, `last_error` | the last failure as a short line such as `AUTH sport`, `EXPIRED login`, `HTTP 503 members/93724` or `AUTH 400 API_KEY_INVALID` (at most 300 characters) |
| `updated_at`, `updated_source`, `updated_by` | the last change of the value: `MIGRATION`, `SEED`, `ROTATION` or `ADMIN`; `updated_by` is the admin, only for `ADMIN` |

The access and ID tokens are issued by a refresh with the refresh token. An
admin sees their state but cannot replace them; replacing the refresh token
clears both, and the next My ITMO request refreshes all three.

Every call of the store runs in its own short transaction (`REQUIRES_NEW`),
independently of the caller's transaction, so a rotated token is committed
before the caller continues and never lost to the caller's rollback. No entity
or lock outlives a call. Missing or invalid credentials degrade the features
that need them (sport refresh, teacher names, the ISU check) without stopping
HTTP. `toString()` of every class holding a value hides it; values never reach
a log, an exception message, the audit or an HTTP response.

## Seeds

The environment variables above are seeds only. At startup the store writes a
trimmed seed only into a row without a value (`MISSING`): on an empty database
or where V8 copied nothing. A stored value always wins, so a stale seed in
`.env` is harmless. A seeded row is `UNKNOWN` with source `SEED`; the refresh
token gets an assumed 30-day expiry so that the My ITMO client tries a refresh,
the cookie learns its expiry at the first login that rotates it, and the Gemini
key has no expiry.

After the first successful use (the refresh token `OK` with source `ROTATION`,
the cookie `OK` with `last_renewed_at` set, the Gemini key `OK` with
`last_used_at` set), remove the seed from `.env` and
recreate `backend`, so the container environment no longer holds the secret:

```bash
docker compose --env-file .env -f compose.yaml up -d backend
```

## Rotation and statuses

- **My ITMO.** The client is MyItmoApi 2.x (`my-itmo-api-kmp`); `MyItmoService`
  is its `TokenStorage`: a read is one snapshot of the three rows, a write is
  one rotation. Before every request `MyItmoApiGateway` lets the client refresh
  an access token that is missing (seeded or replaced refresh token), expired or
  expires within 30 seconds. Every token refresh stores the access, ID and
  refresh token together (`ROTATION`), marks the rows `OK`, sets `last_used_at`
  and `last_renewed_at` of the refresh token and clears its `last_error`. The
  refresh token's expiry is `refresh_expires_in` of the answer (720 hours
  observed); a refresh token with an unknown or past expiry is not sent. A
  failed refresh, rejected by ITMO.ID or not answered, writes nothing: the
  stored refresh token stays. When the sport catalog refresh fails with the
  category `AUTH` (a rejected refresh, or 401/403 from My ITMO), the refresh
  token becomes `FAILED` with `last_error = AUTH sport`. Nothing sets `EXPIRED`
  for My ITMO rows.
- **ISU.** A successful login sets `OK` and `last_renewed_at`; when Keycloak
  issued a different `KEYCLOAK_IDENTITY` during it, the new value is stored
  (`ROTATION`) with the expiry from its `Max-Age`. Successful ISU requests of a
  run set `last_used_at`. A login that ends on `id.itmo.ru` or on the Keycloak
  login form sets `EXPIRED`; any other failure of a login or a request sets
  `FAILED`. An `EXPIRED` cookie is not tried again until it is replaced. An
  `UNKNOWN` cookie is checked by a login at the next run, at most 5 minutes
  later, even without reviews to check.
- **Gemini.** The first valid answer of an AI summary run marks the key `OK`
  and sets `last_used_at`. An `AUTH` failure (HTTP 401 or 403, or the reasons
  `API_KEY_INVALID`, `API_KEY_EXPIRED`) marks it `FAILED` with `last_error` such
  as `AUTH 400 API_KEY_INVALID` and stops the run; the next run tries the key
  again. Other Gemini failures do not change the key's status. The key travels
  only in the `x-goog-api-key` header through `gemini-proxy`
  ([AI summaries](ai-summaries.md#network-and-gemini-proxy)).

`expiresSoon` in the admin API is true when the key has a window, `expires_at`
is known and less than the window remains: renew the refresh token within a day
of its expiry, the cookie within 14 days. A daily login keeps the cookie from
expiring unused ([ISU verification](isu-verification.md#maintenance)).

## Replacement

An admin replaces a value in the web admin («Система» → «Учётные данные» →
«Заменить») or with `PUT /api/admin/system/credentials/{key}` and `{"value": …}`
([admin API](../contracts/admin.md#system)); only `MY_ITMO_REFRESH_TOKEN`,
`ISU_KEYCLOAK_IDENTITY` and `GEMINI_API_KEY` are replaceable. The new value is `UNKNOWN` with source
`ADMIN` and the admin in `updated_by`, the last error is cleared, and one
`SERVICE_CREDENTIAL_REPLACED` audit row with target `credential:<key>` and no
details commits with it. Replacing the refresh token also clears the access and
ID token. Replacing the cookie drops the ISU session in memory, queues every
pending review check at once and starts a run that logs in with it. A new
Gemini key is read by the next AI summary request.

Get the cookie from a browser signed in to `https://id.itmo.ru` with the
technical account: the value of the `KEYCLOAK_IDENTITY` cookie of `id.itmo.ru`.
Create the Gemini key in Google AI Studio (`https://aistudio.google.com`) under
the owner's account; the free tier is enough. Paste values only into the web
admin field; never into a chat, a ticket, a command line or a log.

## Storage and exposure

Values are stored as plain text. Database dumps and the container
environment until `backend` is recreated without seeds contain secrets: keep
dumps under `/mnt/raid/backups/` with mode 0700 and never copy them elsewhere.

Check the state without selecting a value:

```sql
SELECT key, status, value IS NOT NULL AS present, updated_source, expires_at, last_used_at,
       last_renewed_at, last_error
FROM service_credentials ORDER BY key;
```

## Tests

`ServiceCredentialStorePersistenceTest` covers the independent transactions of
the My ITMO client's `TokenStorage` and refreshes, seeds, concurrent rotations, statuses, failures,
replacements with the audit. `V8ServiceCredentialsTest` checks the V8 copy, the
preserved old table and the constraints, `V12DropMyItmoStorageTest` the drop, `V10TeacherSummariesTest` the V10 row; `BackendStartupTest` the seed at startup;
`MyItmoApiGatewayTest` the refresh before a request, the failed refresh that
writes nothing and the failure classes; `AdminCredentialsServiceTest` and
`AdminApiSecurityTest` the admin API.
