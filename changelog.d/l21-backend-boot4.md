# Code structure, build and tests

- Spring Boot 3.5.16 -> 4.1.1: Spring Framework 7, Spring Security 7,
  Hibernate 7.4, Jackson 3.1 (`tools.jackson`; annotations stay
  `com.fasterxml.jackson.annotation`), Flyway 12 through
  `spring-boot-starter-flyway`, Testcontainers 2, JUnit 6 and springdoc 3.1 for
  the OpenAPI snapshot. Kotlin stays 2.2.21. No migration and no wire change:
  every app, admin, web sign-in, request and FCM fixture is unchanged, and the
  released Core 1.2.0 and 1.7.0 decode every one they know.
- Request bodies are read as on Jackson 2: `null` for a JVM primitive still
  reads as its default and content after the JSON value is still ignored
  (`JacksonConfig`), so installed apps get no new 400s. The six enum guards
  still reject numbers, and the strict boolean and lookup deserializers keep
  their rules.
- FCM `data` is written by a copy of Spring's `JsonMapper` that omits `null`
  values and `null` map entries, as before.
- Rolling Spring Boot 4 back is image-only (`docs/ops/deployment.md`).
