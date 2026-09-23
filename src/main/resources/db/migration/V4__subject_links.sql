-- Period-scoped subject links with audiences, revisions for review, and a reusable moderation audit.
-- Polymorphic targets have no FK: their service withdraws cases and removes reports on deletion.
CREATE TABLE user_roles (
    user_id uuid NOT NULL,
    role varchar(16) NOT NULL,
    granted_at timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_user_roles_role CHECK (role IN ('MODERATOR'))
);

CREATE TABLE moderation_cases (
    id uuid PRIMARY KEY,
    target_type varchar(32) NOT NULL,
    target_id uuid NOT NULL,
    status varchar(16) NOT NULL,
    reason varchar(16) NOT NULL,
    opened_at timestamp(6) with time zone NOT NULL,
    resolved_at timestamp(6) with time zone,
    CONSTRAINT ck_moderation_cases_target_type CHECK (target_type IN ('SUBJECT_RESOURCE')),
    CONSTRAINT ck_moderation_cases_status CHECK (status IN ('OPEN', 'RESOLVED', 'WITHDRAWN')),
    CONSTRAINT ck_moderation_cases_reason CHECK (reason IN ('SUBMISSION', 'REPORTS', 'VOTES')),
    CONSTRAINT ck_moderation_cases_resolution CHECK ((status = 'OPEN') = (resolved_at IS NULL))
);
CREATE UNIQUE INDEX uq_moderation_cases_open ON moderation_cases (target_type, target_id) WHERE status = 'OPEN';
CREATE INDEX idx_moderation_cases_status ON moderation_cases (status, opened_at);

-- Decisions are insert-only in the application; restrictions reference this immutable audit.
CREATE TABLE moderation_decisions (
    id uuid PRIMARY KEY,
    case_id uuid NOT NULL,
    moderator_id uuid,
    actor varchar(16) NOT NULL DEFAULT 'MODERATOR',
    action varchar(32) NOT NULL,
    note varchar(500),
    restriction_capability varchar(32),
    restriction_days integer,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT fk_moderation_decisions_case FOREIGN KEY (case_id) REFERENCES moderation_cases (id),
    CONSTRAINT fk_moderation_decisions_moderator FOREIGN KEY (moderator_id) REFERENCES users (id),
    CONSTRAINT ck_moderation_decisions_action CHECK (action IN ('APPROVE', 'REJECT', 'HIDE', 'RESTORE', 'DISMISS', 'RESTRICT_USER', 'HIDE_ALL_BY_USER')),
    CONSTRAINT ck_moderation_decisions_restriction CHECK (
        ((action = 'RESTRICT_USER' AND restriction_capability IS NOT NULL) OR
         (action <> 'RESTRICT_USER' AND restriction_capability IS NULL AND restriction_days IS NULL)) AND
        (restriction_days IS NULL OR restriction_days > 0) AND
        (restriction_capability IS NULL OR restriction_capability IN ('SUBMIT_RESOURCES', 'VOTE', 'REPORT', 'WRITE_REVIEWS', 'ALL'))
    ),
    -- Only a moderator makes arbitrary decisions; the policy may only auto-approve.
    CONSTRAINT ck_moderation_decisions_actor CHECK (
        (actor = 'MODERATOR' AND moderator_id IS NOT NULL) OR
        (actor = 'POLICY' AND moderator_id IS NULL AND action = 'APPROVE')
    )
);
CREATE INDEX idx_moderation_decisions_case ON moderation_decisions (case_id, created_at);

CREATE TABLE user_restrictions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    capability varchar(32) NOT NULL,
    decision_id uuid NOT NULL,
    reason varchar(500) NOT NULL,
    starts_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone,
    revoked_at timestamp(6) with time zone,
    revoked_by uuid,
    CONSTRAINT fk_user_restrictions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_restrictions_decision FOREIGN KEY (decision_id) REFERENCES moderation_decisions (id),
    CONSTRAINT fk_user_restrictions_revoker FOREIGN KEY (revoked_by) REFERENCES users (id),
    CONSTRAINT ck_user_restrictions_capability CHECK (capability IN ('SUBMIT_RESOURCES', 'VOTE', 'REPORT', 'WRITE_REVIEWS', 'ALL')),
    CONSTRAINT ck_user_restrictions_window CHECK (expires_at IS NULL OR expires_at > starts_at)
);
CREATE INDEX idx_user_restrictions_active ON user_restrictions (user_id) WHERE revoked_at IS NULL;

-- Defaults live in ModerationPolicy; new settings do not require seed migrations.
CREATE TABLE moderation_settings (
    key varchar(64) PRIMARY KEY,
    value varchar(200) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    updated_by uuid NOT NULL,
    CONSTRAINT fk_moderation_settings_moderator FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_moderation_settings_key CHECK (key ~ '^[A-Z_]+\.[a-z_]+$')
);

CREATE TABLE moderation_reports (
    id uuid PRIMARY KEY,
    target_type varchar(32) NOT NULL,
    target_id uuid NOT NULL,
    reporter_id uuid NOT NULL,
    reason varchar(16) NOT NULL,
    comment varchar(500),
    created_at timestamp(6) with time zone NOT NULL,
    dismissed_at timestamp(6) with time zone,
    CONSTRAINT fk_moderation_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users (id),
    CONSTRAINT ck_moderation_reports_target_type CHECK (target_type IN ('SUBJECT_RESOURCE')),
    CONSTRAINT ck_moderation_reports_reason CHECK (reason IN ('BROKEN', 'WRONG_SUBJECT', 'SPAM', 'OTHER')),
    CONSTRAINT uq_moderation_reports_reporter UNIQUE (target_type, target_id, reporter_id)
);
CREATE INDEX idx_moderation_reports_target ON moderation_reports (target_type, target_id) WHERE dismissed_at IS NULL;
CREATE INDEX idx_moderation_reports_reporter ON moderation_reports (reporter_id, created_at);

-- The link row carries the owner's current content; audiences see the latest approved revision.
CREATE TABLE subject_links (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    subject_id bigint NOT NULL,
    subject_name varchar(200) NOT NULL,
    period_key varchar(8) NOT NULL,
    category varchar(16) NOT NULL,
    url text NOT NULL,
    normalized_url text NOT NULL,
    title varchar(120),
    visibility varchar(8) NOT NULL,
    score integer NOT NULL DEFAULT 0,
    hidden_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT fk_subject_links_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT ck_subject_links_period CHECK (period_key ~ '^[0-9]{4}-[12]$'),
    CONSTRAINT ck_subject_links_category CHECK (category IN ('SCORES', 'QUEUE', 'MATERIALS', 'TASKS', 'RECORDINGS', 'NOTES', 'EXAM', 'CHAT', 'OTHER')),
    CONSTRAINT ck_subject_links_visibility CHECK (visibility IN ('PRIVATE', 'GROUP', 'FLOW', 'ALL'))
);
CREATE INDEX idx_subject_links_scope ON subject_links (subject_id, period_key);
CREATE INDEX idx_subject_links_owner ON subject_links (owner_id, subject_id, period_key);
CREATE INDEX idx_subject_links_category_score ON subject_links (subject_id, category, score);

-- Revision content is immutable after sending; only the outcome fields change.
CREATE TABLE subject_link_revisions (
    id uuid PRIMARY KEY,
    link_id uuid NOT NULL,
    number integer NOT NULL,
    category varchar(16) NOT NULL,
    url text NOT NULL,
    normalized_url text NOT NULL,
    title varchar(120),
    visibility varchar(8) NOT NULL,
    status varchar(16) NOT NULL,
    submitted_at timestamp(6) with time zone NOT NULL,
    decided_at timestamp(6) with time zone,
    note varchar(500),
    CONSTRAINT fk_subject_link_revisions_link FOREIGN KEY (link_id) REFERENCES subject_links (id) ON DELETE CASCADE,
    CONSTRAINT uq_subject_link_revisions_number UNIQUE (link_id, number),
    CONSTRAINT ck_subject_link_revisions_number CHECK (number > 0),
    CONSTRAINT ck_subject_link_revisions_category CHECK (category IN ('SCORES', 'QUEUE', 'MATERIALS', 'TASKS', 'RECORDINGS', 'NOTES', 'EXAM', 'CHAT', 'OTHER')),
    CONSTRAINT ck_subject_link_revisions_visibility CHECK (visibility IN ('PRIVATE', 'GROUP', 'FLOW', 'ALL')),
    CONSTRAINT ck_subject_link_revisions_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_subject_link_revisions_resolution CHECK ((status = 'PENDING') = (decided_at IS NULL))
);
CREATE UNIQUE INDEX uq_subject_link_revisions_pending ON subject_link_revisions (link_id) WHERE status = 'PENDING';

-- Schedule flows the GROUP/FLOW link was published to, captured from the author at publication.
CREATE TABLE subject_link_audience (
    link_id uuid NOT NULL,
    flow_id bigint NOT NULL,
    PRIMARY KEY (link_id, flow_id),
    CONSTRAINT fk_subject_link_audience_link FOREIGN KEY (link_id) REFERENCES subject_links (id) ON DELETE CASCADE
);
CREATE INDEX idx_subject_link_audience_flow ON subject_link_audience (flow_id);

CREATE TABLE subject_link_votes (
    link_id uuid NOT NULL,
    user_id uuid NOT NULL,
    value smallint NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (link_id, user_id),
    CONSTRAINT fk_subject_link_votes_link FOREIGN KEY (link_id) REFERENCES subject_links (id) ON DELETE CASCADE,
    CONSTRAINT fk_subject_link_votes_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_subject_link_votes_value CHECK (value IN (-1, 1))
);

-- Another student's link the viewer added to their own list.
CREATE TABLE subject_link_saves (
    user_id uuid NOT NULL,
    link_id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (user_id, link_id),
    CONSTRAINT fk_subject_link_saves_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_subject_link_saves_link FOREIGN KEY (link_id) REFERENCES subject_links (id) ON DELETE CASCADE
);
CREATE INDEX idx_subject_link_saves_link ON subject_link_saves (link_id);

CREATE TABLE subject_link_pins (
    user_id uuid NOT NULL,
    subject_id bigint NOT NULL,
    period_key varchar(8) NOT NULL,
    link_id uuid NOT NULL,
    PRIMARY KEY (user_id, subject_id, period_key),
    CONSTRAINT fk_subject_link_pins_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_subject_link_pins_link FOREIGN KEY (link_id) REFERENCES subject_links (id) ON DELETE CASCADE,
    CONSTRAINT ck_subject_link_pins_period CHECK (period_key ~ '^[0-9]{4}-[12]$')
);
CREATE INDEX idx_subject_link_pins_link ON subject_link_pins (link_id);

-- Schedule flows seen in the user's uploaded lessons. Rows survive lesson removal but not the user.
CREATE TABLE user_subject_flows (
    user_id uuid NOT NULL,
    subject_id bigint NOT NULL,
    period_key varchar(8) NOT NULL,
    flow_id bigint NOT NULL,
    group_name text NOT NULL,
    type_id integer NOT NULL,
    last_seen date NOT NULL,
    PRIMARY KEY (user_id, subject_id, period_key, flow_id),
    CONSTRAINT fk_user_subject_flows_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_subject_flows_period CHECK (period_key ~ '^[0-9]{4}-[12]$')
);
CREATE INDEX idx_user_subject_flows_flow ON user_subject_flows (subject_id, period_key, flow_id);
