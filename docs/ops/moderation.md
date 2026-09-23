# Community moderation

Moderation has no Android UI. Use the separate Core moderation client or the
HTTP endpoints below. Work in development first; never mutate production data
for tests. Role assignment and moderation actions are separate from deployment.

## Grant a moderator role

An operator with approved database access grants an already registered account
by ISU. Use the database procedure in [database](database.md); bind `isu` through
psql rather than editing credentials or copying a database dump.

```sql
INSERT INTO user_roles (user_id, role, granted_at)
SELECT id, 'MODERATOR', CURRENT_TIMESTAMP FROM users WHERE isu = :isu
ON CONFLICT (user_id, role) DO NOTHING;
```

## Authenticate safely

Obtain an ITMO.ID **access token** from your own authenticated official ITMO
session on your device. Credentials are entered only on official ITMO pages;
do not transfer the refresh token. An access token normally lasts 30 minutes;
when it expires, obtain a fresh access token through that official session.
Never paste tokens in tickets, logs, source files or shell history.

The helper keeps the token out of command-line arguments. Do not use shell
tracing (`set -x`) or verbose HTTP logging. Examples below use development.

```bash
BASE=https://dev.widgets.alllexey.dev
read -r -s -p 'ITMO.ID access token: ' ITMO_ACCESS_TOKEN; printf '\n'
api() {
  printf 'header = "Authorization: Bearer %s"\n' "$ITMO_ACCESS_TOKEN" |
    curl --fail-with-body --silent --show-error --config - "$@"
}
api "$BASE/api/moderation/cases?status=OPEN"
```

403 `permission_denied` means this authenticated account has no moderator role.
403 `restricted` denotes a restricted user action, not a missing moderator role.

## Decide and inspect

A case targets one revision of a subject link (`targetType` `SUBJECT_RESOURCE`,
target `SubjectLinkTarget`): `revision` is the reviewed content, `link` what
other students currently see with the owner-side status, plus the author, active
reports without reporter identities and the author's history. Set `CASE_ID`,
`RESTRICTION_ID` and `ISU` to the intended synthetic development records after
inspecting the queue. These are identifiers, never credentials.

```bash
api -X POST "$BASE/api/moderation/cases/$CASE_ID/decisions" \
  -H 'Content-Type: application/json' -d '{"action":"APPROVE","note":"Проверено"}'
api -X POST "$BASE/api/moderation/cases/$CASE_ID/decisions" \
  -H 'Content-Type: application/json' \
  -d '{"action":"RESTRICT_USER","note":"Повторный спам","restriction":{"capability":"SUBMIT_RESOURCES","days":7}}'
api "$BASE/api/moderation/restrictions?isu=$ISU"
api -X POST "$BASE/api/moderation/restrictions/$RESTRICTION_ID/revoke"
```

APPROVE/REJECT/HIDE/RESTORE/DISMISS resolve the case. RESTRICT_USER and
HIDE_ALL_BY_USER keep the initiating case open for the next decision; perform
these before the resolving decision. `days: null` means permanent.

- APPROVE publishes a pending revision; REJECT declines it and stores the note
  as the owner's `reviewNote`. REJECT on an approved revision (a `REPORTS` or
  `VOTES` case) withdraws it, and other students fall back to the previous
  approved content of the link, if any.
- HIDE hides the whole link from everybody but its owner; RESTORE may append to
  a resolved case and shows it again. New edits of a hidden link stay hidden.
- HIDE_ALL_BY_USER hides every published link of the author and rejects their
  pending revisions; private links and other authors are unchanged.
- DISMISS dismisses the active reports of the revision.

Only public (`ALL`) links wait for review. Group and flow links, and public
links while premoderation is off, are approved automatically: those cases are
already resolved with an APPROVE decision by `actor=POLICY` and no
`moderatorId`, and appear under `status=RESOLVED`. Deleting a link withdraws its
open cases; closed cases keep their decisions with `target: null`.

## Policy changes

Read current settings, preserve every target and send the full typed policy.
Changing premoderation from true to false **approves the pending submission
queue** (except hidden links), recording your moderator identity. This does not
dismiss report/vote cases. Re-enabling does not undo approvals.

```bash
api "$BASE/api/moderation/settings"
api -X PUT "$BASE/api/moderation/settings" -H 'Content-Type: application/json' \
  -d '{"policies":{"SUBJECT_RESOURCE":{"premoderation":false,"reportThreshold":3,"voteThreshold":-3,"dailySubmissionLimit":20,"dailyReportLimit":10}}}'
api "$BASE/api/moderation/cases?status=RESOLVED"
unset ITMO_ACCESS_TOKEN
```

Thresholds and rolling-day limits are validated on Backend. Do not bypass the
API with direct status/settings updates: that omits authorization, queue
transitions, cache invalidation and audit. SQL is only for granting the role.
The link contract is in [subject links](../contracts/subject-links.md).
