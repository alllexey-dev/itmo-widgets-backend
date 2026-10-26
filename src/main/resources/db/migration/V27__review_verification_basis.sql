-- What proved that the teacher taught the author (ADR 0037): ISU_FLOW (verified_flow_id) or SPORT_TEACHER
-- (the teacher has lessons in the sport catalog). A NULL basis with a flow is a row verified by an image before
-- V27, which never writes the basis, so a rollback stays image-only.
ALTER TABLE teacher_reviews ADD COLUMN verified_basis varchar(16);

UPDATE teacher_reviews SET verified_basis = 'ISU_FLOW' WHERE verification = 'VERIFIED';

ALTER TABLE teacher_reviews DROP CONSTRAINT ck_teacher_reviews_verified_flow;
ALTER TABLE teacher_reviews ADD CONSTRAINT ck_teacher_reviews_verified_basis
    CHECK (verified_basis IN ('ISU_FLOW', 'SPORT_TEACHER'));
ALTER TABLE teacher_reviews ADD CONSTRAINT ck_teacher_reviews_verified_by CHECK (
    CASE verified_basis
        WHEN 'ISU_FLOW' THEN verification = 'VERIFIED' AND verified_flow_id IS NOT NULL
        WHEN 'SPORT_TEACHER' THEN verification = 'VERIFIED' AND verified_flow_id IS NULL
        ELSE (verification = 'VERIFIED') = (verified_flow_id IS NOT NULL)
    END
);

-- Own reviews of coaches written before V27 are trusted at once, as a save from now on does.
UPDATE teacher_reviews
SET verification = 'VERIFIED', verified_basis = 'SPORT_TEACHER', verification_due_at = NULL, verification_checked_at = now()
WHERE verification <> 'VERIFIED' AND teacher_isu IN (SELECT teacher_isu FROM sport_lessons);
