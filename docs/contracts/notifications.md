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
| `SPORT_AGENT_BOOKED_PAYLOAD` | `sportLessons: List<SportLessonDto>` (one), `agentName` | the user's self-hosted agent reported a booking ([connected clients](connections.md#agent-booked-push)) |
| `DEVICE_CONNECTED_PAYLOAD` | `connectionId`, `client`, `name`, `connectedAt` | a new connection was claimed ([connected clients](connections.md#after-a-new-connection)) |
| `MODERATION_NOTICE_PAYLOAD` | `ModerationNotice` | a moderation decision on the user's link or review became final ([moderation notices](#moderation-notices)) |

`FriendshipEventPayload.event` is `REQUEST_RECEIVED` or `REQUEST_ACCEPTED`;
`user` is the actor as `UserData` with capabilities computed for the recipient;
`occurredAt` is the transition time. Core decodes it strictly and fails on
unknown events.

## Transport

firebase-admin sends over the JDK transport (`NetHttpTransport`, set in
`FirebaseConfig`). Its default `ApacheHttp2Transport` runs on the Apache
HttpClient 5 that Spring Boot manages, which since 5.6 decompresses FCM's gzip
answer itself; `google-http-client` then fails to decompress it again
(`Not in GZIP format`) and no push is sent. `FirebaseTransportTest` checks both.

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
| `SPORT_AGENT_BOOKED_PAYLOAD` | end of the booked lesson |
| `DEVICE_CONNECTED_PAYLOAD` | 12 hours after `connectedAt` |
| `MODERATION_NOTICE_PAYLOAD` | 3 days after the notice became due |

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
| `apns-collapse-id` | `sport-<entryId>`, `friend-<actor isu>`, `connection-<id>` or `moderation-<decision id>` |
| `apns-expiration` | the Android deadline above in epoch seconds; `0` once it has passed |
| `aps.mutable-content` | `1` |
| `aps.thread-id` | `sport`, `friends`, `connections` or `moderation` |
| `aps.interruption-level` | `time-sensitive` for sport (`active` on a build without the entitlement), `active` for the rest |
| `aps.alert` | `title-loc-key`, `loc-key` and `loc-args`, no title or body copy |

Each key is a frozen key of the app's string catalog
(`scripts/strings-frozen-keys.txt` in ITMO.Widgets, `PushLocKeys` here), so iOS
renders the Russian text from its own `Localizable`:

| Type | `title-loc-key` | `loc-key` | `loc-args` |
|---|---|---|---|
| `SPORT_*_SIGN_LESSONS_PAYLOAD` | `notification_sport_place_free` | `notification_sport_lesson` | section name, lesson start as `dd.MM HH:mm` in Europe/Moscow |
| `FRIENDSHIP_EVENT_PAYLOAD`, `REQUEST_RECEIVED` | `notification_channel_friends` | `notification_friend_request` | actor's name, the ISU when blank |
| `FRIENDSHIP_EVENT_PAYLOAD`, `REQUEST_ACCEPTED` | `notification_channel_friends` | `notification_friend_accepted` | the same |
| `SPORT_AGENT_BOOKED_PAYLOAD` | `notification_sport_success` | `notification_sport_lesson` | section name, lesson start as `dd.MM HH:mm` in Europe/Moscow |
| `DEVICE_CONNECTED_PAYLOAD`, an agent or MCP server | `notification_device_connected_title` | `notification_device_connected_agent` | the connection's name |
| `DEVICE_CONNECTED_PAYLOAD`, the web | `notification_device_connected_title` | `notification_device_connected_web` | none |
| `MODERATION_NOTICE_PAYLOAD`, a link | `notification_moderation_link_<outcome>` | `notification_moderation_link_body`, `_body_note` for a rejection with a note | the link's title (its host when blank), the subject, the note |
| `MODERATION_NOTICE_PAYLOAD`, a review | `notification_moderation_review_<outcome>` | `notification_moderation_review_body`, `_body_note` for a rejection with a note | the teacher's name (the review's subject when unknown), the note |
| `MODERATION_NOTICE_PAYLOAD`, a review with neither | `notification_moderation_review_<outcome>` | `notification_moderation_review_note` for a rejection with a note, else `_unnamed` | the note, else none |

`<outcome>` is the lowercase `ModerationOutcome`: `approved`, `rejected`, `hidden` or `restored`.

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
  When the owner's [self-hosted agent](connections.md#who-books-a-sport-place) is live
  for `sport`, the attempt goes to its event log instead and the devices get no
  sport push.
- Friendships also go to the event log of an agent live for `friends:read`,
  next to the push.
- Friendships: `FriendService` returns intents; `UserProfileService.act`
  registers them in `afterCommit` and hands them to a bounded executor
  (`friendshipNotificationExecutor`, never the request thread).
  `FriendshipNotificationPayloadService` builds the payload in a short read
  transaction and drops it when the relationship no longer matches the event;
  `FriendshipNotificationService` sends outside any transaction. Rollbacks never
  enqueue; failures only log the exception class.

## Moderation notices

The author of a subject link or a teacher review learns the outcome of a moderation decision on it: Backend writes a
`moderation_notices` row in the decision's transaction (`ModerationService`), the app's bell lists the notices and a
push tells of each one.

| Decision | `ModerationOutcome` |
|---|---|
| A moderator's `APPROVE` of a pending revision; the ISU check's approval of a pending `FLOW` link | `APPROVED` |
| `REJECT` of a pending or an approved revision | `REJECTED` |
| `HIDE` of a shown link or review | `HIDDEN` |
| `RESTORE` of a hidden one | `RESTORED` |

Nothing is told for an `APPROVE` that keeps an approved revision (a report case), `DISMISS`, `RESTRICT_USER`,
`HIDE_ALL_BY_USER`, an automatic approval without premoderation, or a decision a moderator takes on their own
content. Only `REJECTED` carries `note`, the moderator's note the author also sees as `reviewNote`; the notes of other
decisions are for moderators and the ISU check's note is technical.

A moderator's decision is due when its revert window has passed (`itmowidgets.moderation.revert-window`, 10
minutes) plus one minute; a revert deletes the row first, so a reverted decision is never told. The ISU check's
approval cannot be reverted and is due at once. `ModerationNoticeService` checks every minute for due notices, marks
them sent in a short transaction (`FOR UPDATE SKIP LOCKED`) and pushes them outside it, at most 100 at a time; a
notice due more than 3 days ago is marked without a push. A notice whose link or review was deleted is neither pushed
nor listed. Rows go 30 days after they became due and with the account
([account deletion](../ops/account-deletion.md)).

`GET /api/users/me/moderation-notices` answers `ApiResponse<List<ModerationNotice>>`: the caller's notices that
became due in the last 30 days, the newest decision first, at most 50. Authenticated callers only; a connection needs
the `user` scope (the web).

| Type | Fields |
|---|---|
| `ModerationNotice` | `id` (the decision's id), `outcome: APPROVED\|REJECTED\|HIDDEN\|RESTORED`, `note?`, `decidedAt`, `link?`, `review?`; exactly one of `link` and `review` |
| `ModerationNoticeLink` | `id` (the link), `subjectId`, `subjectName`, `periodKey`, `title?` and `host` of the decided revision |
| `ModerationNoticeReview` | `id` (the review), `teacherIsu`, `teacherName?` (null when no name is known), `subjectTitle?` of the decided revision |

Backend has no per-type push preference: the app decides whether to show the push
([ITMO.Widgets notifications](https://github.com/alllexey-dev/ITMO.Widgets/blob/master/docs/features/notifications.md)).

## Device registration

Both routes require authentication and answer `ApiResponse<String>`.

| Route | Body |
|---|---|
| `POST /api/device/register-device` | `RegisterDeviceRequest {fcmToken, deviceName, platform?, alertsAllowed?, appVersion?}` |
| `DELETE /api/device/current` | `UnregisterDeviceRequest {fcmToken}` (a `DELETE` with a body) |

Registration stores the FCM token (trimmed) with a device name for the
authenticated user; a token that is already known moves to that user. A new
token takes over the user's device of the same platform that has no token yet:
one Backend created from the build's `X-App-Version` reports
([devices without push](app-version.md#devices-without-push)). The
optional fields describe the device: `platform` (`"ANDROID"` or `"IOS"`,
absent means `ANDROID`), `alertsAllowed` (absent means `true`) and `appVersion`
(the version name, at most 32 characters; a longer or blank one is ignored).
Released clients send none of them. Every registration applies them to the
device, including a known token, with absent fields reset to their defaults, so
an iOS build that once registered against a Backend without these fields (and
was stored as `ANDROID`) is corrected by its next registration. An absent
`appVersion` keeps the stored one. An `IOS` device with `alertsAllowed: false`
gets no pushes and does not count as a registered device for sport attempts,
and neither does a device without a token
([sport automation](sport-automation.md)); on Android the flag is stored only,
because data messages update widgets whether alerts show or not. `DELETE /api/device/current`
removes the token only when it belongs to the authenticated user and otherwise
changes nothing. Android registers on sign-in and on enabling services and
unregisters on sign-out and on disabling services. Registration also stores the
request's `X-App-Version` on the registered device
([client version header](app-version.md#client-version-header)); without the
header the device keeps the build it reported before; a header's version name
wins over the body's `appVersion`.
