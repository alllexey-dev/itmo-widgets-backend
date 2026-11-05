# 0038 AI rules for open Google Sheets through a host analyzer

**Decision (2026-11-05).** Backend gives the app a rule for reading a
student's points from a Google Sheet: which tab and row are the student's, and
which cell to read or which cells of the own row to add up. The rule is made
once per workbook by a model and applied by the device to the sheet it already
downloads. Amends the app's 0014 (Backend now reads a public, flow-shared
sheet; the device computes values). Contract:
[sheet rules](../contracts/sheet-rules.md); operations:
[runbook](../ops/sheet-rules.md).

**Only open sheets.** A sheet qualifies only through a subject link the
caller sees whose approved revision shares it with a flow or everybody
(`FLOW` or `ALL`). Private, hidden and pending content never reaches the
analyzer: the approved revision decides, not the link row, and the job checks
again before every call that some link still shares the sheet. Such a sheet is
already open to that audience, so reading it adds no new reader. A private
sheet keeps the manual pick on the device.

**Analyzer on the host, Claude Code isolated.** The model runs in
`sheet-analyzer/`, a small Python service on the host (`systemd --user`), not
in Backend. It calls the Claude Code CLI already on the server
(`claude -p`, Sonnet 5.5, effort low) through the owner's egress proxy, with
the isolation of the experiment: input on stdin only, no tools
(`--tools ""`), an empty MCP config, `--setting-sources ""`,
`--no-session-persistence`, `--max-budget-usd`, an empty temp working
directory and a timeout of 420 s. Backend reaches it only through a unix socket
in a directory shared with the backend containers: one JSON line in, one out.
Backend holds no model key and no egress route for it.

**Pseudonymised input.** The analyzer downloads the XLSX export itself (the
formulas are needed), replaces names and ISU with pseudonyms before the model
sees anything, and keeps the workbook and the answer only in a temp directory
removed after the call. Its logs carry no cell values, names, ISU, prompts or
answers; Backend's carry status codes and durations only.

**Only the rule leaves the analyzer.** The analyzer validates the model's
answer against the real workbook (tabs, cells, examples against cached values,
counterexamples) and drops invalid routes; nothing from the model is executed.
What it returns is the sanitised rule: tab names, columns, row numbers and a
closed set of arithmetic nodes, with no examples or values. Backend checks the
shape again with an exact allowlist and rejects the whole rule on any other
key, so nothing else can be stored or served.

**The device applies it.** The device finds the own row by ISU or name in the
CSV it already downloads and computes the sum; a missing or non-numeric input
gives no number, never a guessed 0. Nothing is saved without the user's tap.
Backend never sends other students' data to the device and the device never
uploads the sheet.

**Cost and staleness.** A rule is cached per sheet id with a fingerprint of the
workbook structure (tabs, header rows, formula templates), so new marks do not
change it. A request for a rule older than a day first asks for the
fingerprint and spends a model call only when the structure changed. Model
calls have a daily budget and a per-user hourly limit; one runs at a time.

**Open risk.** The CLI runs on the owner's Claude subscription while the
feature serves other users. Before production, check Anthropic's terms for
this use or switch the analyzer to an API key; until then the feature stays
off in production (`SHEET_RULES_ENABLED=false`).
