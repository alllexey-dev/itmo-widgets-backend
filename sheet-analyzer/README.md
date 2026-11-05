# sheet-analyzer

Host service that turns a public Google Sheet of scores into a validated, sanitised rule: which cell of a
student's own row is the current total, or which bounded sum of own-row cells computes it. Backend (in Docker)
asks it over a local unix socket; the app later applies the rule to the CSV it already downloads.

Python 3 (3.11+), stdlib plus `openpyxl`. It runs on the host because it calls the Claude Code CLI that lives
there (`~/.local/bin/claude`).

## What one `analyze` does

1. Checks the sheet id (`^[A-Za-z0-9_-]{20,100}$`) and downloads, directly and only from
   `https://docs.google.com/`, the XLSX export (`/spreadsheets/d/<id>/export?format=xlsx`, at most 30 MiB, must
   start with `PK`) and `/htmlview` for the tab name -> gid list (at most 10 MiB). Redirects are followed only
   within `https://docs.google.com`; a sign-in redirect, 401 or 403 is `closed`.
2. Detects names and ISU numbers, replaces them with workbook-local pseudonyms (`student_0001`) everywhere,
   including formula literals and formula caches, redacts emails, phones and links, and packs every tab
   losslessly (cached values, formula groups, Google native formulas, notes, styles). The packing refuses to go
   on if any detected name or ISU is still in the text.
3. Computes the structure fingerprint: sha256 of tab names and order, the cells above the first student row and
   the formula templates per column. New marks or new students do not change it.
4. Runs `claude -p` with the isolation flags of the experiment (model `claude-sonnet-5-5`, effort `low`,
   `--tools ""`, empty MCP config, `--setting-sources ""`, `--no-session-persistence`, `--max-budget-usd`,
   egress proxy `http://127.0.0.1:3181`) in an empty temp cwd, prompt and workbook on stdin, timeout 420 s.
   A Markdown code fence around the JSON answer is stripped.
5. Validates every route against the packed workbook (nothing from the model is executed): schema, the one roster
   tab and every referenced tab exist, columns and identity columns exist and hold pseudonymised students,
   the model's formula examples and example cells match the export, example results match the route, and
   `+1` in a bonus or exam column of the roster raises a `current_accumulated` route by exactly 1. Invalid routes
   are dropped.
6. Sanitises the surviving routes into the protocol `rule`: no examples, cell values, names or ISU; pseudonyms in
   free text become `студент`; gids are added from the htmlview list. No route left is `insufficient`.

Raw workbooks stay in memory; the CLI's temp cwd is removed after each call. Logs carry only operations,
statuses, codes, sizes and durations, never cell values, names, ISU, sheet ids, prompts or model output.

## Protocol

One JSON object per connection, UTF-8, `\n`-terminated each way. Request at most 4 KiB.

```json
{"version": 1, "op": "fingerprint", "sheet_id": "<google sheet id>"}
{"version": 1, "op": "analyze", "sheet_id": "<google sheet id>"}
```

```json
{"version": 1, "status": "ok", "fingerprint": "<sha256 hex>"}
{"version": 1, "status": "ok", "fingerprint": "<sha256 hex>", "rule": {"schema_version": 1, "decision": "...", "summary": "...", "tabs": [], "routes": []}}
{"version": 1, "status": "closed", "code": "http_403"}
```

`rule` and route shapes are those of the Backend contract (spec `Backend and analyzer split`). Statuses and codes:

| Status | Codes |
| --- | --- |
| `closed` | `http_401`, `http_403`, `signin_redirect`, `signin_page`, `not_xlsx` |
| `not_found` | `http_404`, `http_410` |
| `too_large` | `content_length`, `body_limit`, `http_413`, `unpacked_too_large`, `input_too_large` |
| `network` | `timeout`, `unreachable`, `io_error`, `offsite_redirect`, `http_<code>` |
| `busy` | `busy`: the same operation is already running (one `analyze` and one `fingerprint` at a time) |
| `model_failed` | `cli_missing`, `timeout`, `cli_error`, `bad_envelope`, `bad_json` |
| `rejected` | answer: `schema`, `wrong_workbook_key`; request: `bad_request`, `request_too_large`, `bad_version`, `bad_op`, `bad_sheet_id`; workbook: `bad_xlsx`, `bad_formula`, `pack_mismatch`, `redaction_failed`; `internal` |
| `insufficient` | `no_valid_route` |

## Configuration

| Variable | Default |
| --- | --- |
| `SHEET_ANALYZER_SOCKET` (or `--socket`) | `/srv/data/sheet-analyzer/run/analyzer.sock` |
| `SHEET_ANALYZER_CLAUDE` | `~/.local/bin/claude` |
| `SHEET_ANALYZER_MODEL` | `claude-sonnet-5-5` |
| `SHEET_ANALYZER_EFFORT` | `low` |
| `SHEET_ANALYZER_BUDGET_USD` | `5` |
| `SHEET_ANALYZER_PROXY` | `http://127.0.0.1:3181` (empty: no proxy variables for the CLI) |
| `SHEET_ANALYZER_MODEL_TIMEOUT` | `420` seconds |
| `SHEET_ANALYZER_MAX_INPUT_BYTES` | `1600000` (packed workbook; larger is `too_large`) |

Google is always reached without a proxy; the proxy applies only to the CLI.

## Development

```bash
cd sheet-analyzer
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python -m unittest
```

Tests are offline: synthetic workbooks with invented people, a fake HTTP layer and a fake `claude` binary.

Modules: `download` (Google), `workbook` (detection, pseudonyms, packing, fingerprint), `evidence` (formula
interpreter and the calculation AST), `validate` (route checks and the sanitised rule), `model` (CLI call),
`service` (operations, busy), `server` (socket, framing).

## Install on the server

As the user that owns `~/.local/bin/claude` (`alllexey`), from a checkout of this repository:

```bash
# once, as root: the socket directory, shared with the group that the backend container's UID is in
sudo install -d -o alllexey -g <backend-group> -m 2770 /srv/data/sheet-analyzer/run

# code and venv
mkdir -p ~/.local/share/sheet-analyzer
cp -r sheet-analyzer/sheet_analyzer sheet-analyzer/requirements.txt ~/.local/share/sheet-analyzer/
python3 -m venv ~/.local/share/sheet-analyzer/.venv
~/.local/share/sheet-analyzer/.venv/bin/pip install -r ~/.local/share/sheet-analyzer/requirements.txt

# service
cp sheet-analyzer/deploy/sheet-analyzer.service ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now sheet-analyzer
loginctl enable-linger "$USER"   # already on if itmo-widgets-agent runs
journalctl --user -u sheet-analyzer -f
```

The directory is setgid, so the socket (mode 0660) gets the shared group. Bind-mount
`/srv/data/sheet-analyzer/run` into the backend containers. Update: copy `sheet_analyzer/` again and
`systemctl --user restart sheet-analyzer`; an analysis in progress is lost and Backend retries it.

Smoke test from the host:

```bash
printf '%s\n' '{"version":1,"op":"fingerprint","sheet_id":"<id>"}' | socat - UNIX-CONNECT:/srv/data/sheet-analyzer/run/analyzer.sock
```
