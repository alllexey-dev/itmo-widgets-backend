# Notifications

- `POST /api/device/register-device` takes the optional `platform`
  (`ANDROID`/`IOS`, default `ANDROID`), `alertsAllowed` (default `true`) and
  `appVersion`; every registration applies them, also to a known token, so an
  iOS device stored as `ANDROID` heals on its next registration. The token is
  trimmed. An iOS device with alerts off gets no pushes and reserves no sport
  attempt. Fixtures `RegisterDeviceRequestIos`, `registerDeviceIos`.
- Admin: `AdminDevice.platform` and `AdminDashboardTotals.devicesByPlatform`
  (fixtures `adminUsers_detail`, `adminDashboard_dashboard`).
- Migration `V13__device_platform.sql` adds `devices.platform`,
  `alerts_allowed`, `push_provider` and `created_at`, all with defaults and
  without a table rewrite; `fcm_token` keeps its name. A rollback to 1.8.0 or
  1.7.0 stays image-only.
- `IOS` devices get APNs alerts instead of data messages: `title-loc-key`,
  `loc-key` and `loc-args` from the app's frozen keys (new
  `notification_sport_place_free`), `mutable-content`, collapse id, thread id,
  interruption level and an expiration at the push's deadline, with the same
  `data` and `recipient_isu`. Android messages are unchanged.
