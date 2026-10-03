# Code structure, build and tests

- No wire change: every golden fixture in `src/test/resources/contract/` is
  unchanged, and the released Core 1.2.0 and 1.7.0 decode every one they know.
- Backend owns its wire DTOs and no longer depends on ITMO.Widgets Core;
  released Core appears only in the `compatCore120Test` and `compatCore170Test`
  suites, and the build uses no `-SNAPSHOT` or `mavenLocal()`.
- Sources are split into `feature/<feature>/{web,service,persistence,model}` and
  `platform/{security,error,config}` (`docs/architecture.md`); the
  `architectureTest` suite checks the package rules with Konsist.
- ktlint runs in `check`; every dependency version lives in
  `gradle/libs.versions.toml`; the Gradle build cache is on, and tests that read
  files outside their classpath declare them as task inputs.
- Tests: the migration suite is one class per script in
  `platform/migration/`, and no test pins the number of migrations any more
  (`MigrationScripts` reads `classpath:db/migration`), so a new `V<n>__*.sql`
  needs no edit to an existing test. Test users come from `testing/TestUsers.kt`
  instead of 20 local `user()` factories. Test containers carry the labels
  `itmo-agents.run`, `itmo-agents.pid` and `itmo-agents.dir`, and
  `scripts/verify.sh leaks` lists the ones a killed test JVM left behind.
