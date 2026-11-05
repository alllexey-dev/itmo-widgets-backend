-- AI rules for open Google Sheets (ADR 0038, docs/ops/sheet-rules.md): how a device computes a student's total from
-- a sheet that is shared with a flow or everybody. One row per Google sheet id; `rule` is the analyzer's sanitised
-- answer and never holds cell values, names or ISU. `fingerprint` is the workbook structure the stored verdict (a
-- rule, or none for INSUFFICIENT) was made for; it is null while there is no verdict.
CREATE TABLE sheet_rules (
    sheet_id varchar(128) PRIMARY KEY,
    status varchar(16) NOT NULL,
    rule jsonb,
    fingerprint varchar(64),
    model varchar(100),
    attempts integer NOT NULL DEFAULT 0,
    last_error varchar(100),
    requested_at timestamp(6) with time zone,
    checked_at timestamp(6) with time zone,
    analyzed_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ck_sheet_rules_sheet_id CHECK (sheet_id ~ '^[A-Za-z0-9_-]{1,128}$'),
    CONSTRAINT ck_sheet_rules_status CHECK (status IN ('PENDING', 'RUNNING', 'READY', 'INSUFFICIENT', 'FAILED')),
    CONSTRAINT ck_sheet_rules_rule CHECK (rule IS NULL OR (jsonb_typeof(rule) = 'object' AND fingerprint IS NOT NULL)),
    CONSTRAINT ck_sheet_rules_rule_status CHECK (rule IS NULL OR status IN ('PENDING', 'RUNNING', 'READY')),
    CONSTRAINT ck_sheet_rules_ready CHECK (status <> 'READY' OR rule IS NOT NULL),
    CONSTRAINT ck_sheet_rules_insufficient CHECK (status <> 'INSUFFICIENT' OR fingerprint IS NOT NULL),
    CONSTRAINT ck_sheet_rules_verdict CHECK (
        (fingerprint IS NULL AND analyzed_at IS NULL AND model IS NULL)
        OR (fingerprint IS NOT NULL AND analyzed_at IS NOT NULL AND model IS NOT NULL)
    ),
    CONSTRAINT ck_sheet_rules_fingerprint CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_sheet_rules_attempts CHECK (attempts >= 0)
);
CREATE INDEX idx_sheet_rules_queue ON sheet_rules (requested_at) WHERE status IN ('PENDING', 'RUNNING');

-- The only row: the running lease of the analysis job and the analyses spent on the current budget day.
CREATE TABLE sheet_rule_state (
    id smallint PRIMARY KEY,
    running_since timestamp(6) with time zone,
    budget_day date,
    budget_used integer NOT NULL DEFAULT 0,
    CONSTRAINT ck_sheet_rule_state_id CHECK (id = 1),
    CONSTRAINT ck_sheet_rule_state_budget CHECK (budget_used >= 0)
);
INSERT INTO sheet_rule_state (id) VALUES (1);
