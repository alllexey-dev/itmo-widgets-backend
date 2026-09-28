-- Own teacher reviews with premoderated revisions and votes, votes on the Reviews copies, and the ISU flow cache.
-- Moderation targets stay polymorphic without a foreign key, as in V4: the review service withdraws cases and
-- removes reports on deletion. The ISU cache keeps only numbers: flow ids and the ISU of teachers and members.
-- The ISU cookie lives in service_credentials (V8). Existing tables only gain a column, an index and wider
-- checks, so the previous image still passes Hibernate validation after an image-only rollback.
ALTER TABLE moderation_cases DROP CONSTRAINT ck_moderation_cases_target_type;
ALTER TABLE moderation_cases ADD CONSTRAINT ck_moderation_cases_target_type
    CHECK (target_type IN ('SUBJECT_RESOURCE', 'TEACHER_REVIEW'));
ALTER TABLE moderation_reports DROP CONSTRAINT ck_moderation_reports_target_type;
ALTER TABLE moderation_reports ADD CONSTRAINT ck_moderation_reports_target_type
    CHECK (target_type IN ('SUBJECT_RESOURCE', 'TEACHER_REVIEW'));
ALTER TABLE moderation_reports DROP CONSTRAINT ck_moderation_reports_reason;
ALTER TABLE moderation_reports ADD CONSTRAINT ck_moderation_reports_reason
    CHECK (reason IN ('BROKEN', 'WRONG_SUBJECT', 'SPAM', 'OTHER', 'OFFENSIVE', 'WRONG_TEACHER'));

-- The author's row carries the current content; others see the latest approved revision.
CREATE TABLE teacher_reviews (
    id uuid PRIMARY KEY,
    author_id uuid NOT NULL,
    teacher_isu integer NOT NULL,
    subject_title varchar(200),
    text text NOT NULL,
    anonymous boolean NOT NULL DEFAULT true,
    score integer NOT NULL DEFAULT 0,
    hidden_at timestamp(6) with time zone,
    verification varchar(16) NOT NULL DEFAULT 'PENDING',
    verified_flow_id bigint,
    verification_due_at timestamp(6) with time zone,
    verification_attempts integer NOT NULL DEFAULT 0,
    verification_checked_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT fk_teacher_reviews_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_teacher_reviews_author_teacher UNIQUE (author_id, teacher_isu),
    CONSTRAINT ck_teacher_reviews_isu CHECK (teacher_isu BETWEEN 100000 AND 9999999),
    CONSTRAINT ck_teacher_reviews_text CHECK (char_length(text) BETWEEN 30 AND 3000),
    CONSTRAINT ck_teacher_reviews_verification CHECK (verification IN ('PENDING', 'VERIFIED', 'UNVERIFIED')),
    CONSTRAINT ck_teacher_reviews_verified_flow CHECK ((verification = 'VERIFIED') = (verified_flow_id IS NOT NULL)),
    CONSTRAINT ck_teacher_reviews_due CHECK ((verification = 'PENDING') = (verification_due_at IS NOT NULL)),
    CONSTRAINT ck_teacher_reviews_attempts CHECK (verification_attempts >= 0)
);
CREATE INDEX idx_teacher_reviews_teacher ON teacher_reviews (teacher_isu);
CREATE INDEX idx_teacher_reviews_due ON teacher_reviews (verification_due_at) WHERE verification = 'PENDING';

-- Revision content is immutable after sending; only the outcome fields change.
CREATE TABLE teacher_review_revisions (
    id uuid PRIMARY KEY,
    review_id uuid NOT NULL,
    number integer NOT NULL,
    subject_title varchar(200),
    text text NOT NULL,
    status varchar(16) NOT NULL,
    submitted_at timestamp(6) with time zone NOT NULL,
    decided_at timestamp(6) with time zone,
    note varchar(500),
    CONSTRAINT fk_teacher_review_revisions_review FOREIGN KEY (review_id) REFERENCES teacher_reviews (id) ON DELETE CASCADE,
    CONSTRAINT uq_teacher_review_revisions_number UNIQUE (review_id, number),
    CONSTRAINT ck_teacher_review_revisions_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_teacher_review_revisions_resolution CHECK ((status = 'PENDING') = (decided_at IS NULL)),
    CONSTRAINT ck_teacher_review_revisions_text CHECK (char_length(text) BETWEEN 30 AND 3000)
);
CREATE UNIQUE INDEX uq_teacher_review_revisions_pending ON teacher_review_revisions (review_id) WHERE status = 'PENDING';

CREATE TABLE teacher_review_votes (
    review_id uuid NOT NULL,
    user_id uuid NOT NULL,
    value smallint NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (review_id, user_id),
    CONSTRAINT fk_teacher_review_votes_review FOREIGN KEY (review_id) REFERENCES teacher_reviews (id) ON DELETE CASCADE,
    CONSTRAINT fk_teacher_review_votes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_teacher_review_votes_value CHECK (value IN (-1, 1))
);

-- Reviews copies keep their UUID across syncs, so their votes survive them.
CREATE TABLE external_teacher_review_votes (
    review_id uuid NOT NULL,
    user_id uuid NOT NULL,
    value smallint NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (review_id, user_id),
    CONSTRAINT fk_external_teacher_review_votes_review FOREIGN KEY (review_id)
        REFERENCES external_teacher_reviews (id) ON DELETE CASCADE,
    CONSTRAINT fk_external_teacher_review_votes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_external_teacher_review_votes_value CHECK (value IN (-1, 1))
);

ALTER TABLE external_teacher_reviews ADD COLUMN score integer NOT NULL DEFAULT 0;

-- Flow ids the author sent with the latest save, candidates for the ISU check.
CREATE TABLE teacher_review_flows (
    review_id uuid NOT NULL,
    flow_id bigint NOT NULL,
    PRIMARY KEY (review_id, flow_id),
    CONSTRAINT fk_teacher_review_flows_review FOREIGN KEY (review_id) REFERENCES teacher_reviews (id) ON DELETE CASCADE,
    CONSTRAINT ck_teacher_review_flows_flow CHECK (flow_id > 0)
);

CREATE TABLE isu_potoks (
    potok_id bigint PRIMARY KEY,
    teachers_checked_at timestamp(6) with time zone,
    members_checked_at timestamp(6) with time zone,
    CONSTRAINT ck_isu_potoks_id CHECK (potok_id > 0)
);

CREATE TABLE isu_potok_teachers (
    potok_id bigint NOT NULL,
    teacher_isu integer NOT NULL,
    PRIMARY KEY (potok_id, teacher_isu),
    CONSTRAINT fk_isu_potok_teachers_potok FOREIGN KEY (potok_id) REFERENCES isu_potoks (potok_id) ON DELETE CASCADE
);
CREATE INDEX idx_isu_potok_teachers_teacher ON isu_potok_teachers (teacher_isu);

CREATE TABLE isu_potok_members (
    potok_id bigint NOT NULL,
    isu integer NOT NULL,
    PRIMARY KEY (potok_id, isu),
    CONSTRAINT fk_isu_potok_members_potok FOREIGN KEY (potok_id) REFERENCES isu_potoks (potok_id) ON DELETE CASCADE
);

CREATE INDEX idx_lessons_teacher ON lessons (teacher_isu) WHERE teacher_isu IS NOT NULL;
