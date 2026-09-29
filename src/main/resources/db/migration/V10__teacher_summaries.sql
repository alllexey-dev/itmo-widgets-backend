-- AI summaries of teacher reviews built through the Gemini API; the Gemini key is a service_credentials row.
-- A summary is built only from active Reviews copies and published own reviews that passed the ISU check
-- (docs/ops/ai-summaries.md). Existing tables only gain a wider check and a row.
ALTER TABLE service_credentials DROP CONSTRAINT ck_service_credentials_key;
ALTER TABLE service_credentials ADD CONSTRAINT ck_service_credentials_key CHECK (
    key IN ('MY_ITMO_REFRESH_TOKEN', 'MY_ITMO_ACCESS_TOKEN', 'MY_ITMO_ID_TOKEN', 'ISU_KEYCLOAK_IDENTITY', 'GEMINI_API_KEY')
);
INSERT INTO service_credentials (key, updated_at) VALUES ('GEMINI_API_KEY', now());

-- One row per teacher: the current input, the summary shown to users and the admin and retry state.
-- The shown content may lag behind the input until a new summary is built.
CREATE TABLE teacher_summaries (
    teacher_isu integer PRIMARY KEY,
    input_hash varchar(64),
    input_count integer NOT NULL DEFAULT 0,
    content text,
    content_hash varchar(64),
    content_count integer,
    level varchar(16),
    confidence varchar(8),
    model varchar(100),
    generated_at timestamp(6) with time zone,
    hidden_at timestamp(6) with time zone,
    hidden_by uuid,
    requested_at timestamp(6) with time zone,
    attempts integer NOT NULL DEFAULT 0,
    last_attempt_at timestamp(6) with time zone,
    last_error varchar(100),
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ck_teacher_summaries_isu CHECK (teacher_isu BETWEEN 100000 AND 9999999),
    CONSTRAINT ck_teacher_summaries_input CHECK (
        (input_hash IS NULL AND input_count = 0) OR (input_hash IS NOT NULL AND input_count >= 3)
    ),
    CONSTRAINT ck_teacher_summaries_content CHECK (
        (content IS NULL AND content_hash IS NULL AND content_count IS NULL AND level IS NULL AND confidence IS NULL
            AND model IS NULL AND generated_at IS NULL)
        OR (content IS NOT NULL AND content_hash IS NOT NULL AND content_count IS NOT NULL AND level IS NOT NULL
            AND confidence IS NOT NULL AND model IS NOT NULL AND generated_at IS NOT NULL)
    ),
    CONSTRAINT ck_teacher_summaries_content_count CHECK (content_count IS NULL OR content_count >= 3),
    CONSTRAINT ck_teacher_summaries_level CHECK (
        level IN ('VERY_NEGATIVE', 'NEGATIVE', 'MIXED', 'POSITIVE', 'VERY_POSITIVE')
    ),
    CONSTRAINT ck_teacher_summaries_confidence CHECK (confidence IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_teacher_summaries_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_teacher_summaries_hidden CHECK (hidden_by IS NULL OR hidden_at IS NOT NULL),
    CONSTRAINT fk_teacher_summaries_hidden_by FOREIGN KEY (hidden_by) REFERENCES users (id) ON DELETE SET NULL
);
CREATE INDEX idx_teacher_summaries_queue ON teacher_summaries (input_count DESC, teacher_isu) WHERE input_hash IS NOT NULL;

-- The only row: the running lease, the last run summary and the Gemini requests spent on the current budget day.
CREATE TABLE teacher_summary_state (
    id smallint PRIMARY KEY,
    running_since timestamp(6) with time zone,
    last_started_at timestamp(6) with time zone,
    last_finished_at timestamp(6) with time zone,
    last_trigger varchar(16),
    last_outcome varchar(24),
    last_error varchar(100),
    last_generated integer NOT NULL DEFAULT 0,
    last_failed integer NOT NULL DEFAULT 0,
    last_requests integer NOT NULL DEFAULT 0,
    budget_day date,
    budget_used integer NOT NULL DEFAULT 0,
    CONSTRAINT ck_teacher_summary_state_id CHECK (id = 1),
    CONSTRAINT ck_teacher_summary_state_trigger CHECK (last_trigger IN ('SCHEDULE', 'ADMIN')),
    CONSTRAINT ck_teacher_summary_state_outcome CHECK (
        last_outcome IN ('COMPLETED', 'BUDGET_EXHAUSTED', 'RATE_LIMITED', 'NO_KEY', 'AUTH_FAILED', 'FAILED')
    ),
    CONSTRAINT ck_teacher_summary_state_counters CHECK (
        last_generated >= 0 AND last_failed >= 0 AND last_requests >= 0 AND budget_used >= 0
    )
);
INSERT INTO teacher_summary_state (id) VALUES (1);
