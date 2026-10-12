-- The ISU check of FLOW link revisions under premoderation: a pending FLOW revision whose author is a member of the
-- flow in ISU and whose flow is small enough is approved without a moderator. isu_check_due_at queues the check like
-- teacher_reviews.verification_due_at; NULL means no check is due (done, never needed or left to moderators).
-- Expand-only: Backend 1.9.1 ignores the columns, so a rollback stays image-only.

ALTER TABLE subject_link_revisions
    ADD COLUMN isu_check_due_at timestamp(6) with time zone,
    ADD COLUMN isu_check_attempts integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_subject_link_revisions_isu_check CHECK (isu_check_due_at IS NULL OR visibility = 'FLOW'),
    ADD CONSTRAINT ck_subject_link_revisions_isu_attempts CHECK (isu_check_attempts >= 0);

CREATE INDEX idx_subject_link_revisions_isu_due ON subject_link_revisions (isu_check_due_at)
    WHERE status = 'PENDING' AND isu_check_due_at IS NOT NULL;

-- FLOW revisions already waiting for a moderator get the check too.
UPDATE subject_link_revisions SET isu_check_due_at = now() WHERE status = 'PENDING' AND visibility = 'FLOW';
