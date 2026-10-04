# App version metadata

Wire changes follow the [compatibility rule](compatibility.md).

Two anonymous endpoints under `/api/app`:

- `GET /api/app/version` → `ApiResponse<String>`, the latest Android version,
  kept for older clients;
- `GET /api/app/version-info?platform=ANDROID|IOS` → `ApiResponse<AppVersionInfo>`
  with the required strings `minVersion`, `latestVersion` and `note` (plain
  text, may be empty, never `null`) of that platform's application.

`platform` is optional and defaults to `ANDROID`: an absent or empty parameter
answers the Android values, as Android 2.1 and 2.2 and the deploy smoke check
read them. The values are case-sensitive; any other value, `ios` included, is
400 `invalid_request`. That 400 is part of the contract and is never relaxed:
Web finds out whether a Backend serves per-platform versions by probing an
unknown platform, which Backends before 1.8.0 answer with 200 and the Android
values.

The values describe the Android and iOS applications, not Backend or Core
artifacts; `minVersion` is advisory metadata, not server-side blocking.

An admin sets the values per platform in the web admin
(`PUT /api/admin/system/app-version?platform=`, see [admin API](admin.md));
they are stored in `app_settings` and take effect at once. A key that is not
stored falls back to the environment below.

| Platform | `app_settings` keys |
|---|---|
| `ANDROID` | `app.latest`, `app.minimum`, `app.note` |
| `IOS` | `app.ios.latest`, `app.ios.minimum`, `app.ios.note` |

| Environment variable | Property | Default |
|---|---|---|
| `APP_VERSION` | `itmowidgets.app.version` | `2.1` |
| `MIN_APP_VERSION` | `itmowidgets.app.min-version` | `2.1` |
| `APP_VERSION_NOTE` | `itmowidgets.app.note` | empty |
| `IOS_APP_VERSION` | `itmowidgets.app.ios.version` | `2.3` |
| `IOS_MIN_APP_VERSION` | `itmowidgets.app.ios.min-version` | `2.3` |
| `IOS_APP_VERSION_NOTE` | `itmowidgets.app.ios.note` | empty |

The iOS defaults equal the first iOS release, so no iOS build is told to update
before an admin sets the iOS keys. The Compose file forwards only the three
Android variables into the container; iOS values are set in the admin. Do not
copy environment files between development and production.
