# Service credentials

Backend keeps the secrets of external services in one table,
`service_credentials` (`V8__service_credentials.sql`): the technical My ITMO
account's tokens, used for the sport catalog and the teacher names of the web
admin, and the ISU `KEYCLOAK_IDENTITY` cookie of the
[ISU verification](isu-verification.md). `ServiceCredentialStore` is the only
reader and writer. An admin sees everything about a value except the value
itself and replaces values through one audited admin API.

## Table

One row per secret; the key is the name of `ServiceCredential`. V8 creates all
four rows, so a row always exists.

| Key | Kind | Replaceable by an admin | Seed variable (property) | «Expires soon» window |
|---|---|---|---|---|
| `MY_ITMO_REFRESH_TOKEN` | `REFRESH_TOKEN` | yes | `MY_ITMO_REFRESH_TOKEN` (`itmowidgets.my-itmo.refresh-token`) | 1 day |
| `MY_ITMO_ACCESS_TOKEN` | `ACCESS_TOKEN` | no | — | — |
| `MY_ITMO_ID_TOKEN` | `ID_TOKEN` | no | — | — |
| `ISU_KEYCLOAK_IDENTITY` | `COOKIE` | yes | `ISU_KEYCLOAK_IDENTITY` (`itmowidgets.isu.keycloak-identity`) | 14 days |

| Column | Meaning |
|---|---|
| `key`, `value` | the name and the secret itself (plain text); `value IS NULL` exactly when `status = 'MISSING'` |
| `expires_at` | when the value expires, if known |
| `status` | `MISSING` (no value), `UNKNOWN` (copied, seeded or replaced and not used yet), `OK`, `EXPIRED`, `FAILED` |
| `last_used_at` | the last successful use |
| `last_renewed_at` | the last renewal at the issuing service: a My ITMO token refresh or an ISU login |
| `last_error_at`, `last_error` | the last failure as a short line such as `AUTH sport`, `EXPIRED login` or `HTTP 503 members/93724` (at most 300 characters) |
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
the cookie learns its expiry at the first login that rotates it.

After the first successful use (the refresh token `OK` with source `ROTATION`,
the cookie `OK` with `last_renewed_at` set), remove the seed from `.env` and
recreate `backend`, so the container environment no longer holds the secret:

```bash
docker compose --env-file .env -f compose.yaml up -d backend
```

## Rotation and statuses

- **My ITMO.** Every token refresh stores the access, ID and refresh token
  together (`ROTATION`), marks the rows with a value `OK`, sets `last_used_at`
  and `last_renewed_at` of the refresh token and clears its `last_error`. The
  refresh token's expiry is `refreshExpiresIn` of the answer (720 hours
  observed). When the sport catalog refresh fails with the category `AUTH`, the
  refresh token becomes `FAILED` with `last_error = AUTH sport`. Nothing sets
  `EXPIRED` for My ITMO rows.
- **ISU.** A successful login sets `OK` and `last_renewed_at`; when Keycloak
  issued a different `KEYCLOAK_IDENTITY` during it, the new value is stored
  (`ROTATION`) with the expiry from its `Max-Age`. Successful ISU requests of a
  run set `last_used_at`. A login that ends on `id.itmo.ru` or on the Keycloak
  login form sets `EXPIRED`; any other failure of a login or a request sets
  `FAILED`. An `EXPIRED` cookie is not tried again until it is replaced. An
  `UNKNOWN` cookie is checked by a login at the next run, at most 5 minutes
  later, even without reviews to check.

`expiresSoon` in the admin API is true when the key has a window, `expires_at`
is known and less than the window remains: renew the refresh token within a day
of its expiry, the cookie within 14 days. A daily login keeps the cookie from
expiring unused ([ISU verification](isu-verification.md#maintenance)).

## Replacement

An admin replaces a value in the web admin («Система» → «Учётные данные» →
«Заменить») or with `PUT /api/admin/system/credentials/{key}` and `{"value": …}`
([admin API](../contracts/admin.md#system)); only `MY_ITMO_REFRESH_TOKEN` and
`ISU_KEYCLOAK_IDENTITY` are replaceable. The new value is `UNKNOWN` with source
`ADMIN` and the admin in `updated_by`, the last error is cleared, and one
`SERVICE_CREDENTIAL_REPLACED` audit row with target `credential:<key>` and no
details commits with it. Replacing the refresh token also clears the access and
ID token. Replacing the cookie drops the ISU session in memory, queues every
pending review check at once and starts a run that logs in with it.

Get the cookie from a browser signed in to `https://id.itmo.ru` with the
technical account: the value of the `KEYCLOAK_IDENTITY` cookie of `id.itmo.ru`.
Paste values only into the web admin field; never into a chat, a ticket, a
command line or a log.

## Migration from `my_itmo_storage`

V8 copies the single `my_itmo_storage` row without changing it:

- a blank or absent token becomes a `MISSING` row;
- a token becomes `UNKNOWN` with source `MIGRATION`;
- `refresh_token_expires_at` and `access_token_expires_at` (epoch milliseconds,
  `0` meaning unknown) become `expires_at`; the ID token has no expiry;
- `ISU_KEYCLOAK_IDENTITY` starts `MISSING`.

`my_itmo_storage` keeps the tokens as of V8 so that an image-only rollback
keeps working. Backend no longer reads or writes it. A separate migration of the
next release drops it (follow-up).

## Image-only rollback

A release with V8 and V9 rolls back only by the image (`platform rollback
<stack>`), without restoring the deployment dump, and only with the owner's
approval. The dump is needed only if data is damaged.

- **Schema.** The previous image passes `spring.jpa.hibernate.ddl-auto=validate`,
  which checks only the tables and columns of its own entities: V8 and V9 only
  add tables, the index `idx_lessons_teacher` and the column
  `external_teacher_reviews.score NOT NULL DEFAULT 0` (the previous sync inserts
  get `0`) and widen the checks of `moderation_cases` and `moderation_reports`.
  No existing column is dropped, renamed or retyped. Flyway 11 ignores applied
  migrations it does not know (`ignoreMigrationPatterns = *:future` by default).
- **My ITMO.** The previous image reads `my_itmo_storage` as of V8 and loses
  nothing. ITMO.ID does not revoke an older refresh token when it issues a new
  one, so that token works until its `refresh_token_expires_at`. After that
  date, with the owner's approval, clear the row, put a fresh seed into `.env`
  and recreate `backend`; the previous image seeds only an empty row:

  ```sql
  UPDATE my_itmo_storage SET refresh_token = NULL, access_token = NULL, id_token = NULL,
      refresh_token_expires_at = 0, access_token_expires_at = 0 WHERE id = 1;
  ```

- **Unknown data.** The previous image does not know `service_credentials`, the
  ISU cookie or the review, vote and ISU cache tables. It does not touch them,
  and a later release continues with them.
- **Lost until the next release.** Own reviews, votes, reports on reviews, the
  ISU check, the credentials admin API and the verification counters.
  `GET /api/teachers/{isu}/reviews` returns `external` again, so an app built
  for `reviews` shows no reviews.
- **Moderation.** The previous `ModerationTargetType` knows only
  `SUBJECT_RESOURCE`, and its `ReportReason` lacks `OFFENSIVE` and
  `WRONG_TEACHER`: while `moderation_cases` holds `TEACHER_REVIEW` cases, its
  case lists for the app and the web admin answer with an error. Before
  switching the image, park those cases with their decisions and reports in the
  schema `rollback_parked` in one transaction:

  ```sql
  BEGIN;
  CREATE SCHEMA IF NOT EXISTS rollback_parked;
  CREATE TABLE rollback_parked.moderation_cases AS
      SELECT * FROM moderation_cases WHERE target_type = 'TEACHER_REVIEW';
  CREATE TABLE rollback_parked.moderation_decisions AS
      SELECT d.* FROM moderation_decisions d JOIN rollback_parked.moderation_cases c ON c.id = d.case_id;
  CREATE TABLE rollback_parked.moderation_reports AS
      SELECT * FROM moderation_reports WHERE target_type = 'TEACHER_REVIEW';
  DELETE FROM moderation_reports WHERE target_type = 'TEACHER_REVIEW';
  DELETE FROM moderation_decisions WHERE case_id IN (SELECT id FROM rollback_parked.moderation_cases);
  DELETE FROM moderation_cases WHERE target_type = 'TEACHER_REVIEW';
  COMMIT;
  ```

  A restriction created by a review decision references it
  (`user_restrictions.decision_id`); then the `DELETE` fails on the foreign key,
  nothing is moved, and the rollback stops for the owner's decision. When the
  release is deployed again, return the rows and drop the schema:

  ```sql
  BEGIN;
  INSERT INTO moderation_cases SELECT * FROM rollback_parked.moderation_cases;
  INSERT INTO moderation_decisions SELECT * FROM rollback_parked.moderation_decisions;
  INSERT INTO moderation_reports SELECT * FROM rollback_parked.moderation_reports;
  DROP SCHEMA rollback_parked CASCADE;
  COMMIT;
  ```

  The previous image is not changed for this.

## Storage and exposure

Values are stored as plain text, as they were in `my_itmo_storage` (which keeps
the tokens as of V8 until it is dropped). Database dumps and the container
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
the My ITMO client, seeds, concurrent rotations, statuses, failures,
replacements with the audit and that the store never touches
`my_itmo_storage`. `PostgreSqlMigrationTest` checks the V8 copy, the preserved
old table and the constraints; `BackendStartupTest` the seed at startup;
`AdminCredentialsServiceTest` and `AdminApiSecurityTest` the admin API.
