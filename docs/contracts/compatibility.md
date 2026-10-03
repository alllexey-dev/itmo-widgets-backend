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

## Status codes

- 403 stays the answer when a privacy audience, a role, `permission_denied`,
  `access_denied`, `restricted` or `csrf` denies the request.
- 401 (`unauthorized`) is reserved for missing or invalid credentials and is
  never used for any of the denials above.
- Today a request to a protected route without valid credentials (no bearer, an
  invalid or expired bearer, no web session) is anonymous and gets 403 from the
  security filter chain. Moving it to 401 is a contract change of its own and is
  documented here when it lands.
