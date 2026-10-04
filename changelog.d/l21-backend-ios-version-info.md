# App version

- `GET /api/app/version-info` takes an optional `platform` query, `ANDROID` or
  `IOS`; without it (or empty) it answers the Android values as before. Any
  other value is 400 `invalid_request`, which Web uses to detect the feature.
  `GET /api/app/version` and the parameterless call are unchanged.
- iOS values live in `app_settings` under `app.ios.latest`, `app.ios.minimum`
  and `app.ios.note`, falling back to `IOS_APP_VERSION`, `IOS_MIN_APP_VERSION`
  and `IOS_APP_VERSION_NOTE` (`itmowidgets.app.ios.*`), defaults `2.3`, `2.3`
  and empty. No migration.
- Admin `GET` and `PUT /api/admin/system/app-version` take the same `platform`
  query, default `ANDROID`; `AdminAppVersion` keeps its shape. iOS changes are
  audited as `APP_VERSION_CHANGED` with details starting `IOS: `.
- New golden fixture `appVersionInfoIos` (`http/app/appVersionInfoIos.json`,
  `minCore` `1.8.0`: no released Core calls it); the Android fixtures are
  unchanged.
