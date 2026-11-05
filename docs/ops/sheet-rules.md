# Sheet rules

Backend asks the host's `sheet-analyzer` for an AI rule of a Google Sheet that
a subject link shares with a flow or everybody, stores it per sheet and serves
it to the app ([contract](../contracts/sheet-rules.md),
[ADR 0038](../decisions/0038-sheet-rules.md)). The analyzer itself (download,
pseudonymisation, the Claude Code call, its own validation) lives in
`sheet-analyzer/` and has its own docs there.

## Analyzer protocol

One JSON object per connection to the unix socket, UTF-8, `\n`-terminated each
way; the connection closes after the answer. Version 1, shared with
`sheet-analyzer/`, so a change needs both sides.

```json
{"version": 1, "op": "fingerprint", "sheet_id": "<google sheet id>"}
{"version": 1, "op": "analyze", "sheet_id": "<google sheet id>"}
```

```json
{"version": 1, "status": "ok", "fingerprint": "<sha256 hex>", "rule": {}}
{"version": 1, "status": "closed", "code": "<short ascii>"}
```

`fingerprint` answers without `rule`; `analyze` with both. Failure statuses
are `closed`, `too_large`, `not_found`, `network`, `busy`, `model_failed`,
`rejected` and `insufficient`. `UnixSocketSheetAnalyzerClient` (a
`SocketChannel` on a `UnixDomainSocketAddress`) reads at most 256 KiB up to the
first newline, parses strictly (one value, no duplicate keys) and maps:

| Answer | Code |
|---|---|
| no socket, connection refused | `unavailable` |
| nothing within `fingerprint-timeout` / `analyze-timeout` | `timeout` |
| another version, an unknown status, a missing or malformed fingerprint or rule, no newline, over 256 KiB, broken JSON | `protocol` |
| a failure status | the status itself |

The analyzer's `code` goes to the log only.

## Storage

`sheet_rules` (V28), one row per sheet id:

| Column | Meaning |
|---|---|
| `status` | `PENDING`, `RUNNING`, `READY`, `INSUFFICIENT`, `FAILED` |
| `rule` | the stored rule (jsonb); set only with `READY`, or with `PENDING`/`RUNNING` while a stale rule is re-checked |
| `fingerprint`, `model`, `analyzed_at` | the stored verdict: a rule, or none for `INSUFFICIENT`; all null without a verdict |
| `attempts`, `last_error` | failed attempts since the last verdict and the last reason code |
| `requested_at`, `checked_at`, `updated_at` | the last request, the last attempt or confirmation, the last write |

`sheet_rule_state` has the only row with the run lease (`running_since`) and
the analyses spent on the current Moscow day (`budget_day`, `budget_used`).
The checks of V28 keep a rule only with its verdict and only in a live status.

## Runs

- A `POST` that queues a sheet (none yet, a verdict checked over `stale-after`
  ago, a failure older than that) commits the row and kicks the job. The job
  runs on the single-thread `sheetRuleExecutor` under the lease; a kick during
  a run is dropped and the run, or the next tick, picks the sheet up.
- A tick every minute starts a run when a sheet is `PENDING`. It is how
  deferred and retried sheets continue.
- Per sheet, oldest request first, each at most once per run:
  1. no budget left today: every pending sheet gets `budget_exhausted` and the
     run stops;
  2. `RUNNING`; no link shares the sheet with a flow or everybody any more:
     `FAILED not_shared`, no analyzer call;
  3. `fingerprint`; the same as the stored one: the verdict stands (`READY` or
     `INSUFFICIENT`, `checked_at` now), nothing spent;
  4. otherwise the old verdict is dropped, one analysis of the budget is
     taken and `analyze` runs; a rule that passes `SheetRuleShape` is
     `READY`, `insufficient` is `INSUFFICIENT` with the fingerprint.
- Failures: `busy` and `unavailable` leave the sheet `PENDING` without an
  attempt and stop the run. `closed`, `not_found` and `too_large` fail the
  sheet at once and drop its verdict. Anything else costs an attempt
  (`PENDING`, then `FAILED` at `max-attempts`), except a failed fingerprint
  check of a stored verdict, which keeps the verdict and its old
  `checked_at`.
- The analyzer is never called inside a transaction; every store write commits
  on its own.
- Startup releases the lease and turns `RUNNING` rows back into `PENDING`; the
  backend is a single instance. The lease is renewed before each sheet and is
  taken over after 30 minutes without renewal.

## Configuration

| Property | Environment | Default |
|---|---|---|
| `itmowidgets.sheet-rules.enabled` | `SHEET_RULES_ENABLED` | `false` |
| `itmowidgets.sheet-rules.socket-path` | `SHEET_ANALYZER_SOCKET` | empty; absolute path required when enabled |
| `itmowidgets.sheet-rules.model` | `SHEET_ANALYZER_MODEL` | `claude-sonnet-5-5` (a label stored with rules; the analyzer picks the model) |
| `itmowidgets.sheet-rules.daily-budget` | `SHEET_RULES_DAILY_BUDGET` | `0`; 1-10000 required when enabled |
| `itmowidgets.sheet-rules.user-requests-per-hour` | `SHEET_RULES_USER_REQUESTS_PER_HOUR` | `5` |
| `itmowidgets.sheet-rules.stale-after` | `SHEET_RULES_STALE_AFTER` | `1d` |
| `itmowidgets.sheet-rules.max-attempts` | - | `3` |
| `itmowidgets.sheet-rules.fingerprint-timeout`, `analyze-timeout` | - | `120s`, `480s` (above the analyzer's 420 s) |

With `enabled = true` Backend does not start without an absolute socket path
and a budget. The budget counts `analyze` calls only, per Moscow day; the
per-user limit is in memory and counts only requests that queue an analysis.
`SheetRulesConfig` holds no secret.

## Deployment

The analyzer runs on the host as user `alllexey` and listens on
`/srv/data/sheet-analyzer/run/analyzer.sock` (directory mode 0770, a group
shared with the UID of the backend containers). `compose.yaml` forwards the
environment above; to turn the feature on in an environment, the server's
copy also needs the socket directory and the group, in a Compose override or
in `compose.yaml` there:

```yaml
services:
  backend:
    group_add:
      - "<gid of the shared group>"
    volumes:
      - type: bind
        source: /srv/data/sheet-analyzer/run
        target: /run/sheet-analyzer
        bind:
          create_host_path: false
```

and in `.env`: `SHEET_RULES_ENABLED=true`,
`SHEET_ANALYZER_SOCKET=/run/sheet-analyzer/analyzer.sock`,
`SHEET_RULES_DAILY_BUDGET=<n>`. Development and production may share the one
analyzer: it takes one sheet at a time and answers `busy` to the other.
Production stays off until the open risk of the ADR (the CLI on the owner's
subscription) is settled.

## Logs

Status codes, counters and durations only; never the sheet id, the rule or
anything from the workbook:

```text
Sheet analyzer op=fingerprint status=ok code=null durationMs=2140
Sheet analyzer op=analyze status=rejected code=examples durationMs=61022
Sheet analyzer rule rejected: route.kind
Sheet rules run ready=1 confirmed=0 insufficient=0 failed=0 deferred=0 analyses=1 durationMs=64310
```

Check the state without selecting `rule`:

```sql
SELECT running_since, budget_day, budget_used FROM sheet_rule_state;
SELECT status, last_error, count(*) FROM sheet_rules GROUP BY 1, 2 ORDER BY 1, 2;
```

To retry one failed sheet at once, an admin sets its `updated_at` back by a
day; the next `POST` queues it with fresh attempts.

## Tests

`SheetRuleServiceTest` covers eligibility errors, the run state machine,
budget, staleness and the fingerprint check, the per-user limit, the lease and
crash recovery on PostgreSQL with a fake analyzer; `SheetRuleShapeTest` the
rule allowlist; `UnixSocketSheetAnalyzerClientTest` the framing, every status
and timeout against an in-process unix socket server; `SheetRulesConfigTest`
the configuration; `SheetRuleControllerSecurityTest` both routes, allowed and
denied; `SubjectLinkServiceTest` the sharing checks; `V28SheetRulesTest` the
schema.
