# API description

- `docs/openapi.json` is Backend's route catalog: generated from the
  controllers by `OpenApiSnapshotTest` (springdoc on the test classpath only),
  every route with its `ApiResponse` payload, tagged by feature, and
  regenerated with `scripts/verify.sh openapi`; the build fails when it is
  stale. Clients generate or check their types against it.
- The sealed types are `oneOf` with a discriminator mapping (`SportQueueEntry`
  and `SportQueue` on `type`, `ModerationCaseTarget` on `targetType`), every
  response schema lists all its properties in `required`, and
  `ErrorDetails.code` lists the error codes. No wire change: the JSON, the
  golden fixtures and the `code` strings are unchanged; the codes are one
  `ErrorCode` enum, documented in `docs/contracts/compatibility.md`.
