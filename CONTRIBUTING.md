# Contributing

## Branches and releases

- `dev` is the working branch. Changes reach it through pull requests; CI
  (`.github/workflows/ci.yml`) runs `./gradlew build` on every pull request.
- Every push to `dev` is built, published as an image and deployed to
  development (`https://dev.widgets.alllexey.dev`) by
  `.github/workflows/deliver.yml`. Once development is healthy, CI moves
  `master` to the same commit. Merging into `dev` is a deploy.
- `master` and `v*` tags are moved only by CI; never push them by hand.
- Production is released from `master` with the `release` workflow
  (`gh workflow run release.yml -f version=X.Y.Z`). The pipeline, rollback and
  the manual fallback are in [docs/ops/deployment.md](docs/ops/deployment.md).

## Pull requests

- Branch from `dev`, open the pull request into `dev`, keep it to one topic.
- The build must pass: tests, ktlint, the architecture test, the OpenAPI
  snapshot and the contract fixtures ([development](docs/development.md)).
- Add tests where the behaviour lives. A new route gets an allowed and a
  denied security test; PostgreSQL behaviour is tested against real PostgreSQL
  through Testcontainers, not H2.
- A wire change follows [wire compatibility](docs/contracts/compatibility.md):
  Backend first, clients follow, no installed app at or above `app.minimum`
  breaks. Say `Contract change: <fixture ids>` in the pull request when
  `docs/openapi.json` or `src/test/resources/contract/` changes.
- Update `docs/` so it describes the new current state, and add a line under
  `## Unreleased` in [CHANGELOG.md](CHANGELOG.md) for anything clients,
  operators or the schema notice. A release renames that heading to the
  version and date.
- Dependencies are releases from Maven Central: no `-SNAPSHOT`, no
  `mavenLocal()`; CI rejects both.

## Migrations

- A schema change is a new `src/main/resources/db/migration/V<n>__*.sql` with
  the next free number. A migration is never edited once it has reached `dev`.
- Expand only: nothing the previous release still reads is dropped or renamed,
  so a release always rolls back by the image.
- Every migration has its own test class in
  `src/test/kotlin/dev/alllexey/itmowidgets/backend/platform/migration/`.
- The full rules are in [docs/ops/database.md](docs/ops/database.md#migrations).

## Secrets and data

Never commit `deploy/.env`, Firebase keys, tokens, database dumps or real user
data; tests use synthetic users from `src/test/.../testing/TestUsers.kt`. Never
test against production data.
