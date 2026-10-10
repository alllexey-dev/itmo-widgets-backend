# Notifications

Wire changes follow the [compatibility rule](compatibility.md).

Backend sends FCM messages (data-only on Android, alerts on iOS) to every
registered device of a user. There is no durable outbox: delivery is best effort
after commit, and a process crash between commit and send can lose one message.

## Message shape

Two string keys:

- `data` — the JSON envelope `{ "type": "<TYPE>", "payload": { ... } }`
  (`FcmTypedWrapper`), written by a copy of Spring's `ObjectMapper` that omits
  `null` fields (HTTP responses still write them); date-times are ISO-8601 with
  offset, and `FcmPayload.getType()` stays out of the payload;
- `recipient_isu` — the ISU of the account the message is for. Android drops a
  message whose recipient is not the signed-in user.

| Type | Payload | Sent when |
|---|---|---|
| `SPORT_FREE_SIGN_LESSONS_PAYLOAD` | `sportLessons: List<SportLessonDto>` | a free-queue attempt is reserved for a lesson with a free place |
| `SPORT_AUTO_SIGN_LESSONS_PAYLOAD` | `sportLessons: List<SportLessonDto>` | an auto-queue attempt is reserved for a matched real lesson |
| `FRIENDSHIP_EVENT_PAYLOAD` | `event`, `user`, `occurredAt` | a friend request was received or accepted |

`FriendshipEventPayload.event` is `REQUEST_RECEIVED` or `REQUEST_ACCEPTED`;
`user` is the actor as `UserData` with capabilities computed for the recipient;
`occurredAt` is the transition time. Core decodes it strictly and fails on
unknown events.

## Android delivery options

`PushMessageFactory` builds the firebase-admin message for each device. Every
Android message is data-only: no `notification` block at the top level or in
`android`, because 2.0.1 would display it itself and 2.1/2.2 would not receive
it in `onMessageReceived` in the background. Each Android message carries
`android.priority = high` and a TTL until the moment it stops being actionable,
measured from the injected `Clock`, clamped to zero and to FCM's 28-day maximum:

| Type | Expires at |
|---|---|
| `SPORT_AUTO_SIGN_LESSONS_PAYLOAD` | end of the matched real lesson |
| `SPORT_FREE_SIGN_LESSONS_PAYLOAD` | the entry's eligibility deadline: one hour before start, or lesson end for force entries |
| `FRIENDSHIP_EVENT_PAYLOAD` | 12 hours after `occurredAt` |

The deadlines are those of [sport automation](sport-automation.md) § Deadlines
and cadence; a TTL of zero means FCM tries once and does not store the message.

## iOS delivery options

An `IOS` device gets an alert instead: iOS throttles silent pushes and never
delivers them after a force-quit, while an alert with `mutable-content` wakes the
Notification Service Extension (NSE). The message has no `androidConfig` and no
top-level `notification`; FCM passes `data` and `recipient_isu` as custom keys of
the APNs payload, identical to the Android message. `apnsConfig` carries:

| Field | Value |
|---|---|
| `apns-push-type` | `alert` |
| `apns-priority` | `10` |
| `apns-collapse-id` | `sport-<entryId>` or `friend-<actor isu>` |
| `apns-expiration` | the Android deadline above in epoch seconds; `0` once it has passed |
| `aps.mutable-content` | `1` |
| `aps.thread-id` | `sport` or `friends` |
| `aps.interruption-level` | `time-sensitive` for sport (`active` on a build without the entitlement), `active` for friendships |
| `aps.alert` | `title-loc-key`, `loc-key` and `loc-args`, no title or body copy |

Each key is a frozen key of the app's string catalog
(`scripts/strings-frozen-keys.txt` in ITMO.Widgets, `PushLocKeys` here), so iOS
renders the Russian text from its own `Localizable`:

| Type | `title-loc-key` | `loc-key` | `loc-args` |
|---|---|---|---|
| `SPORT_*_SIGN_LESSONS_PAYLOAD` | `notification_sport_place_free` | `notification_sport_lesson` | section name, lesson start as `dd.MM HH:mm` in Europe/Moscow |
| `FRIENDSHIP_EVENT_PAYLOAD`, `REQUEST_RECEIVED` | `notification_channel_friends` | `notification_friend_request` | actor's name, the ISU when blank |
| `FRIENDSHIP_EVENT_PAYLOAD`, `REQUEST_ACCEPTED` | `notification_channel_friends` | `notification_friend_accepted` | the same |

The device books a sport place after the push, so Backend cannot know the
outcome: the sport alert is a neutral "a place is free", which the NSE shows as
is when it cannot book and retitles with `notification_sport_success` or
`_failure` otherwise. An alert whose estimated APNs payload exceeds 4096 bytes
is not sent; one lesson or one actor stays far below it. Real delivery needs the APNs key in Firebase; tests assert
the built `Message`.

## Delivery

- `DeviceService.sendDataMessageToUser` loads immutable device targets and calls
  FCM with no database transaction open. An `UNREGISTERED` device is removed only
  when its id and token still match, so cleanup cannot delete a replacement.
- Sport: `SportNotificationDeliveryService` rechecks that the reserved attempt is
  still current before sending; see [sport automation](sport-automation.md).
- Friendships: `FriendService` returns intents; `UserProfileService.act`
  registers them in `afterCommit` and hands them to a bounded executor
  (`friendshipNotificationExecutor`, never the request thread).
  `FriendshipNotificationPayloadService` builds the payload in a short read
  transaction and drops it when the relationship no longer matches the event;
  `FriendshipNotificationService` sends outside any transaction. Rollbacks never
  enqueue; failures only log the exception class.

## Device registration

Both routes require authentication and answer `ApiResponse<String>`.

| Route | Body |
|---|---|
| `POST /api/device/register-device` | `RegisterDeviceRequest {fcmToken, deviceName, platform?, alertsAllowed?, appVersion?}` |
| `DELETE /api/device/current` | `UnregisterDeviceRequest {fcmToken}` (a `DELETE` with a body) |

Registration stores the FCM token (trimmed) with a device name for the
authenticated user; a token that is already known moves to that user. The
optional fields describe the device: `platform` (`"ANDROID"` or `"IOS"`,
absent means `ANDROID`), `alertsAllowed` (absent means `true`) and `appVersion`
(the version name, at most 32 characters; a longer or blank one is ignored).
Released clients send none of them. Every registration applies them to the
device, including a known token, with absent fields reset to their defaults, so
an iOS build that once registered against a Backend without these fields (and
was stored as `ANDROID`) is corrected by its next registration. An absent
`appVersion` keeps the stored one. An `IOS` device with `alertsAllowed: false`
gets no pushes and does not count as a registered device for sport attempts
([sport automation](sport-automation.md)); on Android the flag is stored only,
because data messages update widgets whether alerts show or not. `DELETE /api/device/current`
removes the token only when it belongs to the authenticated user and otherwise
changes nothing. Android registers on sign-in and on enabling services and
unregisters on sign-out and on disabling services. Registration also stores the
request's `X-App-Version` on the registered device
([client version header](app-version.md#client-version-header)); without the
header the device keeps the build it reported before; a header's version name
wins over the body's `appVersion`.
