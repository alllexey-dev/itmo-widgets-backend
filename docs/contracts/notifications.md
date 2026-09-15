# Notifications

Backend sends FCM data messages to every registered device of a user. There is
no durable outbox: delivery is best effort after commit, and a process crash
between commit and send can lose one message.

## Message shape

Two string keys:

- `data` — the JSON envelope `{ "type": "<TYPE>", "payload": { ... } }` from
  Core's `FcmTypedWrapper`;
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

`POST /api/device/register-device` stores the FCM token with a device name for
the authenticated user; `POST /api/device/unregister-device` removes it. Android
registers on sign-in and on enabling services and unregisters on sign-out and
on disabling services.
