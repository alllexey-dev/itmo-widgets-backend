# App version metadata

Backend `1.2.0-SNAPSHOT` exposes two anonymous endpoints under `/api/app`:

- `GET /api/app/version` retains `ApiResponse<String>` for existing clients.
- `GET /api/app/version-info` returns `ApiResponse<AppVersionInfo>` with three
  required strings: `minVersion`, `latestVersion`, and `note`.

Both the legacy string and `latestVersion` read the same `AppConfig.version`.
`note` is plain text, not HTML or Markdown; an empty string is valid. This change
only publishes metadata: it adds no UI, version comparison or access restriction
and does not change authentication or database schema.

## Environment configuration

| Environment variable | Configuration property | Default |
|---|---|---|
| `APP_VERSION` | `itmowidgets.app.version` | `2.1` |
| `MIN_APP_VERSION` | `itmowidgets.app.min-version` | `2.1` |
| `APP_VERSION_NOTE` | `itmowidgets.app.note` | Empty string |

Minimum and latest are currently both `2.1`. The Android development build is
`2.1-SNAPSHOT`; these strings describe different release/development roles and
must not be rewritten by a speculative version comparator.

The development deployment inspected on 2026-09-08 explicitly set
`APP_VERSION=2.0.2`. That environment value overrides the new source default.
Before the next separately approved development deployment, set `APP_VERSION=2.1`
and `MIN_APP_VERSION=2.1`; omit `APP_VERSION_NOTE` or set it to an empty string.
Ensure Docker Compose forwards these variables into the app container; editing
`.env` alone does not add new variables to an explicit Compose environment list.
Do not copy environment files between development and production. Verify both
anonymous endpoints after that deployment and confirm their latest values match.
No deployment is requested or performed by this source change.
