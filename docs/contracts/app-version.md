# App version metadata

Two anonymous endpoints under `/api/app`:

- `GET /api/app/version` → `ApiResponse<String>`, the latest Android version,
  kept for older clients;
- `GET /api/app/version-info` → `ApiResponse<AppVersionInfo>` with the required
  strings `minVersion`, `latestVersion` and `note` (plain text, may be empty).

Both read the same configured version. These describe the Android application,
not Backend or Core artifacts; `minVersion` is advisory metadata, not
server-side blocking.

| Environment variable | Property | Default |
|---|---|---|
| `APP_VERSION` | `itmowidgets.app.version` | `2.1` |
| `MIN_APP_VERSION` | `itmowidgets.app.min-version` | `2.1` |
| `APP_VERSION_NOTE` | `itmowidgets.app.note` | empty |

The Compose file forwards these three variables into the container; do not copy
environment files between development and production.
