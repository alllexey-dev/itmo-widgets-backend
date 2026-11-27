-- What the author of a subject link or a teacher review is told about a moderation decision on it
-- (docs/contracts/notifications.md, Moderation notices). Written in the decision's transaction for the decisions the
-- author should know of; a revert deletes its row, which is never due before the revert window has passed. due_at is
-- when the push may go and the bell may list it; sent_at is when the push went out or was skipped as stale.
CREATE TABLE moderation_notices (
    decision_id uuid PRIMARY KEY,
    recipient_id uuid NOT NULL,
    target_type varchar(32) NOT NULL,
    target_id uuid NOT NULL,
    outcome varchar(16) NOT NULL,
    note varchar(500),
    decided_at timestamp(6) with time zone NOT NULL,
    due_at timestamp(6) with time zone NOT NULL,
    sent_at timestamp(6) with time zone,
    CONSTRAINT fk_moderation_notices_decision FOREIGN KEY (decision_id) REFERENCES moderation_decisions (id) ON DELETE CASCADE,
    CONSTRAINT fk_moderation_notices_recipient FOREIGN KEY (recipient_id) REFERENCES users (id),
    CONSTRAINT ck_moderation_notices_target_type CHECK (target_type IN ('SUBJECT_RESOURCE', 'TEACHER_REVIEW')),
    CONSTRAINT ck_moderation_notices_outcome CHECK (outcome IN ('APPROVED', 'REJECTED', 'HIDDEN', 'RESTORED')),
    CONSTRAINT ck_moderation_notices_note CHECK (note IS NULL OR outcome = 'REJECTED')
);

CREATE INDEX idx_moderation_notices_recipient ON moderation_notices (recipient_id, due_at DESC);
CREATE INDEX idx_moderation_notices_unsent ON moderation_notices (due_at) WHERE sent_at IS NULL;
