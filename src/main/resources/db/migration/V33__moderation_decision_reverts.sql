-- Undo of a moderation decision (docs/contracts/admin.md, Moderation). A revertible decision records at insert what
-- it replaced: its target revision's status, decided_at and note, the hidden_at and updated_at of the revision's link
-- or review, and its case's resolved_at (NULL: the case was open). Decisions written before V33 or by an older image
-- have no snapshot and cannot be reverted, so a rollback stays image-only.
ALTER TABLE moderation_decisions
    ADD COLUMN prior_revision_status varchar(16),
    ADD COLUMN prior_decided_at timestamp(6) with time zone,
    ADD COLUMN prior_note varchar(500),
    ADD COLUMN prior_hidden_at timestamp(6) with time zone,
    ADD COLUMN prior_edited_at timestamp(6) with time zone,
    ADD COLUMN prior_case_resolved_at timestamp(6) with time zone;
ALTER TABLE moderation_decisions ADD CONSTRAINT ck_moderation_decisions_prior CHECK (
    (prior_revision_status IS NULL AND prior_decided_at IS NULL AND prior_note IS NULL AND prior_hidden_at IS NULL AND
     prior_edited_at IS NULL AND prior_case_resolved_at IS NULL) OR
    (prior_revision_status IS NOT NULL AND prior_revision_status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN') AND
     (prior_revision_status = 'PENDING') = (prior_decided_at IS NULL) AND prior_edited_at IS NOT NULL AND
     actor = 'MODERATOR' AND action IN ('APPROVE', 'REJECT', 'HIDE', 'RESTORE', 'DISMISS'))
);

-- A revert is a row of its own, so moderation_decisions stays insert-only; a decision is reverted at most once.
CREATE TABLE moderation_decision_reverts (
    decision_id uuid PRIMARY KEY,
    reverted_by uuid NOT NULL,
    reverted_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT fk_moderation_decision_reverts_decision FOREIGN KEY (decision_id) REFERENCES moderation_decisions (id),
    CONSTRAINT fk_moderation_decision_reverts_user FOREIGN KEY (reverted_by) REFERENCES users (id)
);

-- The DISMISS decision that dismissed a report; its revert makes exactly those reports active again.
ALTER TABLE moderation_reports ADD COLUMN dismissed_decision_id uuid;
ALTER TABLE moderation_reports ADD CONSTRAINT fk_moderation_reports_dismissed_decision
    FOREIGN KEY (dismissed_decision_id) REFERENCES moderation_decisions (id);
ALTER TABLE moderation_reports ADD CONSTRAINT ck_moderation_reports_dismissed_decision
    CHECK (dismissed_decision_id IS NULL OR dismissed_at IS NOT NULL);
CREATE INDEX idx_moderation_reports_dismissed_decision ON moderation_reports (dismissed_decision_id)
    WHERE dismissed_decision_id IS NOT NULL;

-- The HIDE_ALL_BY_USER decision whose sweep withdrew the case; NULL when the author withdrew it or deleted the target,
-- and for cases withdrawn before V33.
ALTER TABLE moderation_cases ADD COLUMN withdrawn_by_decision uuid;
ALTER TABLE moderation_cases ADD CONSTRAINT fk_moderation_cases_withdrawn_by_decision
    FOREIGN KEY (withdrawn_by_decision) REFERENCES moderation_decisions (id);
ALTER TABLE moderation_cases ADD CONSTRAINT ck_moderation_cases_withdrawn_by_decision
    CHECK (withdrawn_by_decision IS NULL OR status = 'WITHDRAWN');
