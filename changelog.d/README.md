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
`CHANGELOG.md` with `scripts/changelog.sh`:

```bash
scripts/changelog.sh check                              # names and format of every fragment
scripts/changelog.sh collect <version> <date> --dry-run # the diff, nothing written
scripts/changelog.sh collect <version> <date>           # at release only
scripts/changelog.sh --self-test
```

`collect` inserts `## <version> — <date>` below the preamble (or dates an
existing `## <version> — development` heading), puts bullets without an area
right below it and each `# <Area>` group under `### <Area>`, then deletes the
fragments; this README stays. Commit `CHANGELOG.md` and the deletions together.
`check` also wants one tight list: no blank lines between bullets, continuation
lines indented, no trailing whitespace, a final newline.

The script is a verbatim copy of `scripts/changelog.sh` in ITMO.Widgets
(L02 G-08); change it there first and copy it back unchanged.
