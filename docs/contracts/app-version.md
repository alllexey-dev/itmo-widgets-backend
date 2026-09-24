# App version metadata

Two anonymous endpoints under `/api/app`:

- `GET /api/app/version` → `ApiResponse<String>`, the latest Android version,
  kept for older clients;
- `GET /api/app/version-info` → `ApiResponse<AppVersionInfo>` with the required
  strings `minVersion`, `latestVersion` and `note` (plain text, may be empty).

Both read the same values. These describe the Android application, not Backend
or Core artifacts; `minVersion` is advisory metadata, not server-side blocking.

An admin sets the values in the web admin (`PUT /api/admin/system/app-version`,
see [admin API](admin.md)); they are stored in `app_settings` under
`app.latest`, `app.minimum` and `app.note` and take effect at once. A key that
is not stored falls back to the environment below.

| Environment variable | Property | Default |
|---|---|---|
| `APP_VERSION` | `itmowidgets.app.version` | `2.1` |
| `MIN_APP_VERSION` | `itmowidgets.app.min-version` | `2.1` |
| `APP_VERSION_NOTE` | `itmowidgets.app.note` | empty |

The Compose file forwards these three variables into the container; do not copy
environment files between development and production.
