# Changelog fragments

Unreleased changes are written here, one file per topic, instead of into
[`CHANGELOG.md`](../CHANGELOG.md), so that parallel branches never edit the same
lines. A release collects them into `CHANGELOG.md` and deletes them in the same
commit.

## Fragment

- Name: `<lane-id-lowercase>-<topic>.md`, for example `l21-backend-push-v2.md`.
  It matches `^l[0-9]{2}-[a-z0-9][a-z0-9-]*\.md$`: lowercase only, because the
  file system on the developers' Macs is case-insensitive.
- Content: an optional first line `# <Area>`, then bullets in the voice of
  `CHANGELOG.md`: what changed for clients, operators or the schema, with routes,
  migrations and settings named exactly. No per-day subsections.
- One fragment per topic; only the branch that owns the topic edits it, and later
  work on the same topic extends the same file. A wire change also names the
  routes or fixtures it touches, so client lanes can re-sync.

## Collecting at a release

The release commit turns the fragments into a section below the preamble of
`CHANGELOG.md`:

1. Insert `## <version> — <date>` below the preamble.
2. Put each `# <Area>` group under `### <Area>`.
3. Delete the collected fragments; this README stays.

`scripts/changelog.sh` (`check`, and `collect <version> <date> [--dry-run]`)
automates both steps once it is copied into this repository; until then the
release commit does them by hand and review checks the names.
