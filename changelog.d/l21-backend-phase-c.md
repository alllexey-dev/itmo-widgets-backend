# Code structure, build and tests

- No wire change: the app fixtures in `src/test/resources/contract/` are
  unchanged, and the released Core 1.2.0 and 1.7.0 decode every one they know.
- Spring Boot 3.5.6 → 3.5.16, the last open-source patch of 3.5 (Spring
  Framework 6.2.19, Hibernate 6.6.53, Jackson 2.21.4); `spring-retry` 2.0.13 is
  pinned in the version catalog, where Spring Boot 4 stops managing it. The test
  sources already compile against Spring Boot 4's nullness (`TestEntityManager`,
  `WebServer`, a mocked transaction manager), so the Spring Boot 4 step changes
  only the versions, Jackson 3, Testcontainers 2 and springdoc 3.
- The admin (`/api/admin/**`) and web sign-in (`/api/web/auth/**`) routes have
  response fixtures in `http/admin/` and `http/weblogin/`, named after their
  `docs/openapi.json` operationIds; no released Core reads them (`minCore`
  `1.8.0`). They pin the response shape Web reads.
- MyITMO calls go through `MyItmoGateway` in Backend types: the sport catalog,
  sign limits and the directory lookups no longer touch MyItmoApi models, and a
  MyITMO failure comes back as `MyItmoResult.Failure` instead of an exception.
- FCM `data` is written by a copy of Spring's `ObjectMapper` that omits `null`
  fields, instead of MyItmoApi's Gson; date-times now carry seconds
  (`12:00:00+03:00`), which every released app reads. The three FCM fixtures are
  unchanged.
- `ScheduleController` reads lessons and checks schedule privacy through
  `ScheduleService`; no controller imports persistence types any more, and the
  denial stays 403 `permission_denied`.
- Entities take their creation time from the caller's injected `Clock` instead
  of `now()` defaults.
