# Client versions

- Backend reads the `X-App-Version` header that apps send from 2.3 on
  (`2.3.0-beta.1 (20291); android; github`) and keeps the last build of each
  device: `POST /api/device/register-device` stores it on the registered
  device, any other bearer request on the caller's one device of that
  platform. Malformed or oversized headers, anonymous and web session requests
  are ignored; an unchanged build is written at most once per
  `itmowidgets.client-version.refresh` (default `1h`).
- Migration `V11__device_app_version.sql`: nullable `devices.app_version`,
  `app_build`, `app_platform`, `app_distribution`, `app_version_seen_at`;
  expand-only, no defaults, no backfill, no table rewrite, so 1.7.0 runs on
  the new schema.
- Admin: `AdminDevice` in `GET /api/admin/users/{isu}` gains `appVersion`,
  `appBuild`, `appPlatform`, `appDistribution`, `appVersionSeenAt`; new
  `GET /api/admin/system/client-versions` counts active devices per build for
  the last 7 and 30 days with an `unknownDevices` bucket for Android 2.2 and
  older. Fixtures `adminUsers_detail`, `adminSystem_clientVersions`.
