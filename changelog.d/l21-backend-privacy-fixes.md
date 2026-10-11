# Fixes
- Deleted accounts: the placeholder that keeps published links and reviews no
  longer has `isu = -<ISU>`; it takes a negative ISU from the new sequence
  `deleted_user_isu_seq`, and its audit targets follow.
  `V14__deleted_user_placeholders.sql` renumbers the existing placeholders.
  `AccountDeletionService` and `docs/ops/account-deletion.sql` changed
  together; the runbook's report names the placeholder's ISU for the check.
  `GET /api/users/{isu}` answers for a placeholder (negative ISU) instead of
  400, and Backend no longer asks My ITMO about a non-positive ISU.
- `spring.jpa.open-in-view=false`: a request no longer holds a database
  connection while profiles are decorated with My ITMO groups. Users load with
  their groups, and `PUT /api/users/me/id-token` writes name and picture
  through the managed user.
- Moderation: a decision on a case about the moderator's own content is 403
  `permission_denied`, and so is `RESTRICT_USER` against a moderator or an
  admin unless the caller is an admin. Switching link premoderation off leaves
  the acting moderator's own submissions pending.
- Subject links: with premoderation on, a `FLOW` revision is `PENDING` with an
  open `SUBMISSION` case like an `ALL` one; flow membership comes from the
  uploaded schedule and proved nothing.
- `PUT /api/users/me/id-token` keeps the stored groups when the `groups` claim
  cannot be parsed instead of clearing them; the log line names only the error
  class. The `@Retryable` on `UserGroupUpdater` that could not retry inside
  the caller's transaction is gone.
