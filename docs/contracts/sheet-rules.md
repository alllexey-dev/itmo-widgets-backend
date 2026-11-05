# Sheet rules

Wire changes follow the [compatibility rule](compatibility.md). Why and how:
[ADR 0038](../decisions/0038-sheet-rules.md); operations:
[sheet rules runbook](../ops/sheet-rules.md).

A sheet rule tells a device how to read or compute a student's points from a
Google Sheet that is shared with a flow or everybody. Backend asks the host's
`sheet-analyzer` once per workbook and keeps only the analyzer's sanitised
rule; the device applies it to the sheet it already downloads. Backend never
sends sheet values, names or ISU to the device, and the device never uploads
the sheet.

## Eligibility

A link qualifies only when all of these hold, checked on every call:

- the caller sees the link (`SubjectLinkViews.shown`): it is not hidden, not
  private, and has an approved revision whose audience includes the caller (the
  owner always sees an approved own link);
- that approved revision is `FLOW` or `ALL`;
- its normalized URL is a Google sheet,
  `https://docs.google.com/spreadsheets/d/<id>` (`ResourceUrlPolicy`).

The approved revision decides, never the link row, so a pending edit to another
URL or a pending widening never reaches the analyzer. Rules are stored per
sheet id: every eligible link to the same sheet shares one rule, whatever its
category. Before each analyzer call the job checks again that some link still
shares the sheet with a flow or everybody; otherwise the sheet fails with
`not_shared` and the analyzer is not called.

## Routes

Responses use `ApiResponse<T>`; the actor comes from authentication and both
routes are 401 `unauthorized` anonymously. Connected agents and MCP servers
have no scope for them; the web reaches them through the `user` scope.

| Route | Response data | Status |
|---|---|---|
| `GET /api/links/{id}/sheet-rule` | `SheetRuleResponse` | 200; never starts an analysis |
| `POST /api/links/{id}/sheet-rule` | `SheetRuleResponse` | 202 while `PENDING` or `RUNNING`, else 200 |

`POST` queues an analysis when the sheet has none yet, when its verdict was
checked longer than `stale-after` ago (default a day) or when it failed longer
than that ago (with fresh attempts). Otherwise it answers the stored state and
queues nothing. Clients poll `GET` every few seconds while the status is
`PENDING` or `RUNNING`; an analysis takes 20-80 s, a structure check a few.

## `SheetRuleResponse`

```json
{
  "status": "READY",
  "rule": { "schema_version": 1, "...": "see Rule" },
  "analyzedAt": "2026-10-04T12:00:00Z",
  "reason": null
}
```

| Field | Values |
|---|---|
| `status` | `NONE`, `PENDING`, `RUNNING`, `READY`, `INSUFFICIENT`, `FAILED` |
| `rule` | the stored rule object or `null` |
| `analyzedAt` | when the stored verdict (a rule, or none for `INSUFFICIENT`) was made, or `null` |
| `reason` | a reason code or `null` |

- `NONE`: nobody has asked for this sheet yet. Everything else is `null`.
- `PENDING`: queued; `RUNNING`: the job is on it. After a stale `READY` the
  previous `rule` stays set and valid while its structure is checked; after a
  changed structure it becomes `null` before the new analysis.
- `READY`: `rule` is set; `reason` is `null`.
- `INSUFFICIENT`: the analyzer found no usable route; `reason` is
  `insufficient`. An unchanged workbook stays insufficient without a new
  analysis.
- `FAILED`: no rule; `reason` says why. A new `POST` retries a day later.

`rule` is `null` with `INSUFFICIENT` and `FAILED`.

### Reason codes

An open set: clients show a generic message for an unknown code.

| Code | Status | Meaning |
|---|---|---|
| `closed` | `FAILED` | the sheet is not open to anyone with the link (sign-in page, 401 or 403) |
| `not_found` | `FAILED` | Google does not know the sheet |
| `too_large` | `FAILED` | the export is over the analyzer's 30 MiB |
| `not_shared` | `FAILED` | no link shares the sheet with a flow or everybody any more |
| `insufficient` | `INSUFFICIENT` | the analyzer found no route it could check |
| `network`, `model_failed`, `rejected`, `timeout`, `protocol`, `invalid_rule` | `PENDING`, then `FAILED` after 3 attempts | a failed attempt; the job retries it about a minute later |
| `busy`, `unavailable` | `PENDING` | the analyzer was busy or unreachable; no attempt is spent |
| `budget_exhausted` | `PENDING` | the day's analyses are spent; it waits for the next Moscow day |

`rejected` means the analyzer's own check of the model's answer against the
workbook failed; `invalid_rule` means Backend's shape check failed;
`protocol` is a broken analyzer answer; `timeout` is no answer within the
operation's timeout.

### Rule

The analyzer's sanitised answer (protocol version 1), passed through as JSON.
Backend checks its shape before storing it and rejects the whole rule on
anything else, so no example, cell value, name or ISU can ride along: exactly
these keys, these enums, at most 64 KiB of JSON.

```json
{
  "schema_version": 1,
  "decision": "direct|compute|mixed",
  "summary": "short Russian text",
  "tabs": [{"sheet": "name", "gid": "123" | null, "role": "active_roster|source|rubric|template|archive|empty|auxiliary"}],
  "routes": [{
    "id": "r1",
    "purpose": "current_accumulated|official_total",
    "kind": "direct|compute",
    "sheet": "roster tab name", "gid": "123" | null,
    "identity": [{"sheet": "name", "gid": "123" | null, "isu_column": "B" | null,
                  "name_column": "D" | null, "first_student_row": 4}],
    "source": {"sheet": "name", "gid": "123" | null, "column": "R"} | null,
    "calculation": node | null,
    "blank_policy": "preserve|ignore_blank_inputs",
    "explanation": "short Russian text",
    "review_required": true,
    "warnings": ["short Russian text"]
  }]
}
```

- `direct` routes have `source` and no `calculation`; `compute` routes the
  other way round. `review_required` is always `true`: nothing is saved on the
  device without the user's tap.
- `identity` names at least one of `isu_column` and `name_column`; the device
  prefers ISU, then the normalized full name, and never joins by row position.
- Nodes: `{"op":"cell","sheet","gid","column"}` (the student's own row, found
  by the identity of that tab), `{"op":"number","value"}`,
  `{"op":"sum|multiply|min|max","args":[node, ...]}` (1-200 arguments),
  `{"op":"subtract|divide","args":[node, node]}`,
  `{"op":"round","arg":node,"digits":0..6}`. At most 20 levels and 2000 nodes.
- Columns are 1-3 capital letters, `gid` a string of digits or `null`, texts
  non-blank (`summary` up to 500 characters, `explanation` 1000, a warning
  500, a tab name 200), at most 100 tabs, 100 routes with unique `id`s, 10
  identities and 20 warnings per route.

A missing or non-numeric input of a computed route makes the result
unavailable on the device (a dash), never a guessed 0.

## Errors

| Status | `error.code` | `error.message` | When |
|---|---|---|---|
| 400 | `invalid_request` | | the link id is not a UUID |
| 404 | `not_found` | | the link does not exist, or the caller does not see it, or its approved content is not `FLOW`/`ALL` |
| 409 | `business_rule_violation` | `not_a_google_sheet` | the caller sees the link, but it is not a Google sheet |
| 409 | `business_rule_violation` | `sheet_rules_disabled` | `POST` while `itmowidgets.sheet-rules.enabled=false` |
| 429 | `rate_limited` | | `POST` that would queue an analysis, over the per-user limit (default 5 per hour) |

A `GET` works while the feature is disabled and answers what is stored. A
`POST` that only reads the stored state does not count against the limit. The
daily budget is not an error: the sheet waits with `budget_exhausted`.
