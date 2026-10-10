# Wire compatibility

Every contract in this folder is read by clients that are already installed and
cannot be updated by a Backend release: Android 2.1 and 2.2 decode responses
with released Core, Web `/app` with its own TypeScript client. This file is the
rule every wire change follows; each contract file points here.

## Backend first, clients follow

A wire change lands in Backend first, with its tests, and reaches `dev`; Core,
Android, iOS and Web consume it afterwards. A client never ships a call or a
field that the deployed Backend does not serve yet, and a Backend change is safe
for every client in the field the day it lands: it never relies on a client
update.

## Generated OpenAPI document

[`docs/openapi.json`](../openapi.json) describes every route and its
`ApiResponse` payload, tagged by feature. It is generated: springdoc, on the
test classpath only, reads the controllers in `OpenApiSnapshotTest`, which fails
when the file differs from the code. Regenerate it with
`scripts/verify.sh openapi` after a route or wire-type change and after every
rebase; never edit or merge it by hand. A changed file is a contract change:
the pull request says `Contract change: openapi`, and the change follows the
rules below.

The document describes the wire, not only the Kotlin types:

- The sealed types are `oneOf` with a discriminator and its mapping:
  `SportQueueEntry` and `SportQueue` on `type` (`free`, `auto`),
  `ModerationCaseTarget` on `targetType` (`SUBJECT_RESOURCE`, `TEACHER_REVIEW`).
  Each subtype lists every property itself, the discriminator included. Main
  code carries only `@Schema` annotations for this, compiled against
  `swagger-annotations-jakarta` and absent at runtime; the sport types keep
  `type` as a plain property, without `@JsonTypeInfo`.
- A schema reached from a response lists every property in `required`:
  Backend writes nulls, so a nullable property is `required` with `null` among
  its types. A schema only sent also requires every constructor parameter
  without a default. `ModerationDecision.restriction` is `DecisionRestriction`,
  the response twin of the request's `RestrictionRequest` (same JSON, `days`
  always written).
- `ErrorDetails.code` lists the [error codes](#error-codes).

## Supported clients

`app.minimum` (`minVersion` of `GET /api/app/version-info`, see
[app version](app-version.md)) is the oldest Android version Backend must keep
working. It stays `2.1` until Android 2.2 is in Play production, may rise to
`2.2` after that, and never goes above `2.2` during the v2.3 cycle (Backend
1.8.x). The rules below hold while it is at most `2.2`.

| Client | Decoder | Error mapping | What limits Backend |
|---|---|---|---|
| Android 2.0.x (code 3) | Core 1.1.6 | n/a | Told to update (`minVersion` 2.1). Its only live call is `GET /api/app/version`, read with `data!!`. FCM: an unknown `type` is only logged; a `notification` block is shown as is |
| Android 2.1, 2.1.1 (codes 4, 5) | Core 1.2.0 | 401 → unauthorized, 403 → forbidden | Unknown fields are skipped. Throwing enums: `RelationshipState`, `FriendshipEvent`, `SharingVisibility`; the sport `type` discriminator is closed. `AppVersionInfo` requires the strings `minVersion`, `latestVersion` and `note` |
| Android 2.2 (code 6) | Core 1.7.0 | same, plus 403 `restricted` → restricted | 22 of 23 enums are strict; an unknown `RestrictionCapability` becomes `ALL`, an unknown `QueueEntryStatus` becomes `null` in a non-null field (a later crash). Required fields and duplicate keys are enforced, unknown keys skipped |
| Web `/app` | hand-written TypeScript | session lost on 401 anywhere or 403 on `/api/web/auth/me` | Deployed separately from Backend |

## Allowed

- New routes.
- New optional response fields.
- New FCM `type`s in the `data` envelope (old clients log and drop them).
- New optional request fields with a default that keeps the old behaviour, and
  new request-only enums.

## Forbidden

- A new value in an existing response enum. Moderation-only enums reach only
  Web and curl; check the client list above before treating one as safe.
- Removing or renaming a field or a route.
- Making a field nullable, or `note: null` in `version-info` (`note` stays a
  string, empty when there is nothing to say).
- A new response field the client must send back.
- A `notification` block on Android pushes: Android messages stay data-only.

## Kept wire details

- The envelope `ApiResponse{success, data, error{message, code}}` and its `code`
  strings.
- The sport queue `type` property (`free`, `auto`) without `@JsonTypeInfo`, the
  `QueueEntryStatus` constant names (also database values), and the `entries`
  default of `UserSportBookingsResponse`.
- The request field `fcmToken`; the FCM keys `data` (`{type, payload}`, nulls
  omitted) and `recipient_isu` ([notifications](notifications.md)).
- Legacy routes: `GET /api/app/version`, `POST /api/device/register-device`,
  `DELETE /api/device/current` with a body,
  `POST /api/sport/auto-sign/queue/current` (a read), and mark-satisfied by
  entry ID ([sport automation](sport-automation.md#routes)).
- `/api/app/**` stays anonymous; the deploy health check calls the
  parameterless `GET /api/app/version-info`
  ([deployment](../ops/deployment.md#delivery-pipeline)).

## Error codes

`error.code` is one of the strings below (`ErrorCode` in `platform/error`).
They never change; a new one is a contract change, and released Android tells
errors apart only by the HTTP status and `restricted`.

| Code | Status | When |
|---|---|---|
| `invalid_request` | 400 | Unreadable body, malformed path or query parameter, unknown enum name; another framework 4xx keeps its own status |
| `invalid_request_data` | 400 | A readable value breaks a documented rule |
| `validation_error` | 400 | Bean validation rejected a request field |
| `unauthorized` | 401 | Missing or invalid credentials (see [status codes](#status-codes)) |
| `reauth_required` | 401 | A web session signed in too long ago for `/api/admin/**` and `/api/moderation/**` ([web](web.md#fresh-sign-in-for-admin-routes)) |
| `permission_denied` | 403 | Privacy, ownership or role denies the caller |
| `access_denied` | 403 | Spring Security denied the call |
| `restricted` | 403 | A moderation restriction blocks the action |
| `csrf` | 403 | A web-session request other than `GET`/`HEAD` without `X-Web-Request: 1` ([web](web.md)) |
| `recent_sign_in_required` | 403 | `DELETE /api/users/me` without a sign-in within `itmowidgets.account.recent-auth` ([privacy](privacy.md#account-deletion)) |
| `not_found` | 404 | Missing resource or route |
| `business_rule_violation` | 409 | The request conflicts with a business rule |
| `conflict` | 409 | The resource already exists |
| `rate_limited` | 429 | Too many requests |
| `internal_server_error` | 500 | Anything else; details stay in the server log |

## Status codes

- 403 stays the answer when a privacy audience, a role, `permission_denied`,
  `access_denied`, `restricted` or `csrf` denies the request.
- 401 (`unauthorized`) is reserved for missing or invalid credentials and is
  never used for any of the denials above. The only other 401 is
  `reauth_required` (golden fixture `errors/reauth_required.json`): valid web
  session credentials that admin and moderation routes no longer accept.
- A request to a protected route without valid credentials (no bearer, an
  invalid or expired bearer, no or an expired web session) is anonymous and gets
  401 `unauthorized` with `WWW-Authenticate: Bearer` from the security filter
  chain, before any controller runs (golden fixture `errors/unauthorized.json`).
  Released Android maps 401 to its "unauthorized" error and has no automatic
  action on it (no sign-out, no retry change); Web treats it as a lost session.
- Before Backend 1.8.0 the same request got 403 with an empty body, so a client
  that must also talk to an older Backend keeps treating 403 on
  `/api/web/auth/me` as a lost session.
- A verified token whose caller cannot be resolved (a database failure) is 500
  `internal_server_error`, never 401.
