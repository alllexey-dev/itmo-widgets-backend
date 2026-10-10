# Architecture

Backend is one Spring Boot application, split by feature. A change to one
feature stays inside its directory; code that every feature uses lives in
`platform`. Paths below are relative to
`src/main/kotlin/dev/alllexey/itmowidgets/backend` unless they start with `src/`.

## Package map

`Application.kt` and two package trees, nothing else:

| Package | Owns |
|---|---|
| `feature/app` | `/api/app/**`: app version metadata (`AppVersionSettings`, `AppConfig`) |
| `feature/push` | devices, `/api/device/**`, FCM delivery (`FcmService`, `DeviceService`, `DeviceDeliveryStore`), the FCM wire types and each device's last reported app build (`ClientVersionFilter`, `ClientVersionService`) |
| `feature/users` | users, roles, study groups, privacy settings, profiles and capabilities, `/api/users/**` |
| `feature/social` | friendships, `/api/friends/**`, friendship notifications |
| `feature/schedule` | the uploaded schedule snapshot, flow membership, `/api/schedule/**` |
| `feature/sport` | the sport catalog refresh, auto-sign and free-sign queues, their notifications, `/api/sport/**` |
| `feature/links` | subject links, revisions, votes and pins |
| `feature/moderation` | cases, reports, decisions, restrictions and policies, the moderator routes |
| `feature/reviews` | teacher reviews, the Reviews sync, the ISU check, AI summaries |
| `feature/admin` | `/api/admin/**` behind the web admin; one controller, service and models file per admin page |
| `feature/weblogin` | the phone-approved web login and `iw_session` |
| `feature/credentials` | service credentials and the MyITMO client (`MyItmoService`) |
| `platform/security` | `SecurityConfig`, the JWT and web session filters, `ItmoJwtVerifier` |
| `platform/error` | `ApiResponse`, `ServiceException` and its subclasses, `GlobalExceptionHandler`, `SafeDiagnostics` |
| `platform/config` | the `Clock` bean and `@EnableRetry` |
| `platform/http` | `OutboundHttpClient`, the one `java.net.http` wrapper for calls to other services: timeouts, body limit, redirect policy, redacted failure texts (`HttpRedaction`) |

Each feature has up to four layers:

| Layer | Holds |
|---|---|
| `web` | controllers and the wire types of the feature: response and request bodies, wire enums of those bodies, FCM payloads |
| `service` | services, feature `@Configuration` and `@ConfigurationProperties`, values passed between services (notification intents, results) |
| `persistence` | Spring Data repositories and JPQL row types |
| `model` | JPA entities, domain enums and rules, values a repository returns to a service (`SportQueueCandidate`) |

The admin wire types are split per page: `AdminPage.kt` (paging, shared),
`AdminDashboardModels.kt`, `AdminUsersModels.kt`, `AdminSystemModels.kt`,
`AdminModerationModels.kt`, `AdminReviewsModels.kt`, `AdminAuditModels.kt`.

## Rules

- A controller calls services; it does not read repositories, check privacy or
  build entities.
- `web` holds only what goes over the wire. A value that never leaves Backend
  lives in `service` or `model`.
- A feature uses another feature through its services and models, not through
  its `web` package.
- `platform` knows no feature.
- Time comes from the injected `Clock`: `Instant.now(clock)`, never `now()`.
- Privacy and authorization are decided in services and covered by the
  controller security tests; see [Privacy](contracts/privacy.md).

`ArchitectureTest` checks four of them with Konsist on `src/main/kotlin`: `web`
never depends on `persistence`, no feature depends on another feature's `web`,
`platform` depends on no feature, no `now()` without a `Clock`. A file depends
on every class it imports and every class it names by its full name, JPQL
`SELECT new …` strings included. The test lives in its own suite,
`src/architectureTest/kotlin/dev/alllexey/itmowidgets/backend/ArchitectureTest.kt`,
so Konsist's Kotlin compiler stays off the Spring tests' classpath; `check`
runs it, and so does `scripts/verify.sh run -- architectureTest`.

Today's violations are listed per rule in allowlists in the test source, one
entry per line. The test fails on a new violation and on an entry that no
longer occurs, so the lists only shrink: the change that fixes a violation
removes its entry. Most cross-feature entries are wire types that responses of
several features share (`UserData`, `GroupData`, `UserProfile`, `FcmPayload`)
and admin rows that other features build.

## Where things go

| Adding | Goes to |
|---|---|
| a route | a controller in `feature/<f>/web`; the security rule in `platform/security/SecurityConfig` if it is not plain `authenticated()`; an allowed and a denied test in `src/test/.../feature/<f>/web/*SecurityTest.kt` |
| a response or request type | `feature/<f>/web`, following [Wire compatibility](contracts/compatibility.md); a route used by released apps also gets a golden fixture in `src/test/resources/contract/` |
| an FCM payload | `feature/push/web/FcmModels.kt` or the sending feature's `web`, with a fixture in `src/test/resources/contract/fcm/` |
| an entity or a domain enum | `feature/<f>/model` |
| a repository or a JPQL row | `feature/<f>/persistence`; `SELECT new` names the class with its full package |
| a migration | `src/main/resources/db/migration/V<n>__*.sql`, numbered by the integrator; see [Database](ops/database.md#migrations) |
| a configuration property | a `@ConfigurationProperties` class in `feature/<f>/service`, documented in the feature's page under `docs/ops` or `docs/contracts` |
| a unit or service test | the mirrored package under `src/test/kotlin/dev/alllexey/itmowidgets/backend/feature/<f>/` |
| a PostgreSQL test | a subclass of `platform/PostgreSqlRepositoryTest`; migration and startup pins in `platform/PostgreSqlMigrationTest` and `platform/BackendStartupTest` |
| a contract test | `src/test/kotlin/dev/alllexey/itmowidgets/backend/contract/`; released Core decodes the fixtures in `src/compatCore120Test` and `src/compatCore170Test` |
