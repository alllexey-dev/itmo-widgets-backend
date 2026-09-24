-- A copy of anonymous teacher reviews from the Reviews project, refreshed by a daily sync.
-- Reviews of teachers without an ISU number are not stored; removed upstream reviews keep their row with removed_at.
CREATE TABLE external_teacher_reviews (
    id uuid PRIMARY KEY,
    provider varchar(32) NOT NULL,
    external_id bigint NOT NULL,
    teacher_isu integer NOT NULL,
    teacher_name text NOT NULL,
    subject_title text,
    source_title text,
    source_link text,
    date_raw text NOT NULL,
    written_on date,
    written_before_year integer,
    text text NOT NULL,
    first_seen_at timestamp(6) with time zone NOT NULL,
    last_seen_at timestamp(6) with time zone NOT NULL,
    removed_at timestamp(6) with time zone,
    CONSTRAINT uq_external_teacher_reviews_external UNIQUE (provider, external_id),
    CONSTRAINT ck_external_teacher_reviews_provider CHECK (provider IN ('REVIEWS_WORK_GD')),
    CONSTRAINT ck_external_teacher_reviews_isu CHECK (teacher_isu BETWEEN 100000 AND 9999999),
    CONSTRAINT ck_external_teacher_reviews_date CHECK (written_on IS NULL OR written_before_year IS NULL),
    CONSTRAINT ck_external_teacher_reviews_seen CHECK (last_seen_at >= first_seen_at)
);
CREATE INDEX idx_external_teacher_reviews_teacher ON external_teacher_reviews (teacher_isu) WHERE removed_at IS NULL;

-- One row per provider: the source ETag, the running lease and the last run summary.
CREATE TABLE external_review_sync_state (
    provider varchar(32) PRIMARY KEY,
    etag varchar(200),
    running_since timestamp(6) with time zone,
    last_checked_at timestamp(6) with time zone,
    last_changed_at timestamp(6) with time zone,
    last_success_at timestamp(6) with time zone,
    last_outcome varchar(16),
    last_error varchar(300),
    last_added integer NOT NULL DEFAULT 0,
    last_updated integer NOT NULL DEFAULT 0,
    last_removed integer NOT NULL DEFAULT 0,
    teachers_total integer NOT NULL DEFAULT 0,
    reviews_total integer NOT NULL DEFAULT 0,
    CONSTRAINT ck_external_review_sync_state_provider CHECK (provider IN ('REVIEWS_WORK_GD')),
    CONSTRAINT ck_external_review_sync_state_outcome CHECK (last_outcome IN ('UNCHANGED', 'UPDATED', 'FAILED')),
    CONSTRAINT ck_external_review_sync_state_counters CHECK (
        last_added >= 0 AND last_updated >= 0 AND last_removed >= 0 AND teachers_total >= 0 AND reviews_total >= 0
    )
);
INSERT INTO external_review_sync_state (provider) VALUES ('REVIEWS_WORK_GD');
