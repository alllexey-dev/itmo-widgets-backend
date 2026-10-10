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

## Client version header

Apps from 2.3 on send their build on every Backend request:

```
X-App-Version: <versionName> (<versionCode>); <platform>; <distribution>
X-App-Version: 2.3.0-beta.1 (20291); android; github
```

`platform` is `android` or `ios`; `distribution` names the channel the build
came from (`github`, `play`, `appstore`, ...). Backend reads the header
strictly: at most 100 characters; a version name of 2 to 4 dot-separated
numbers with an optional `-suffix`, at most 32 characters; a version code from
1 to 999999999; lowercase platform and distribution (a letter, then letters,
digits or `-`, at most 16), separated by exactly `; `. A missing or malformed
header is ignored and never fails a request. Android 2.2 and older send none.

Backend keeps only the last build each device reported, on its `devices` row
(`app_version`, `app_build`, `app_platform` as `ANDROID`/`IOS`,
`app_distribution`, `app_version_seen_at`), and shows it only to admins
([admin API](admin.md#users), [system](admin.md#system)). Requests name no
device, so the build is stored where it is unambiguous:

- `POST /api/device/register-device` stores it on the device it registers.
- Any other request authenticated by a bearer token stores it on the caller's
  one device of that platform: a device that last reported the same platform,
  or for `android` also a device that never reported one (every device
  registered before 2.3 is an Android one). With no such device, or with two,
  nothing is stored. Anonymous and web session requests are ignored.

An unchanged build is written at most once per
`itmowidgets.client-version.refresh` (default `1h`), checked in memory before
any database access, so `app_version_seen_at` is precise to that interval; a
changed build is written at once. Privacy and retention are in
[privacy](privacy.md#storage).
