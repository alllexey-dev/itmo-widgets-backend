-- Self-hosted agents: per-user access and event sequence, personal agent tokens and the per-user event log.

-- A row appears when an admin lets a user in or the user's first event is appended.
-- access_granted_at NULL means no access; last_event_seq is the newest seq handed out, never reused.
CREATE TABLE agent_users (
    user_id uuid PRIMARY KEY,
    access_granted_at timestamp(6) with time zone,
    last_event_seq bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_agent_users_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_users_seq CHECK (last_event_seq >= 0)
);

-- Only the SHA-256 of a token is stored; hint is the first characters after the iwa_ prefix.
CREATE TABLE agent_tokens (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    name varchar(60) NOT NULL,
    token_hash char(64) NOT NULL,
    token_hint varchar(12) NOT NULL,
    scopes varchar(100) NOT NULL,
    created_via varchar(8) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    last_used_at timestamp(6) with time zone,
    last_poll_at timestamp(6) with time zone,
    itmo_session_ok boolean NOT NULL DEFAULT false,
    acked_seq bigint,
    revoked_at timestamp(6) with time zone,
    CONSTRAINT fk_agent_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_agent_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_agent_tokens_via CHECK (created_via IN ('PAIR', 'APP', 'WEB')),
    CONSTRAINT ck_agent_tokens_scopes CHECK (scopes <> ''),
    CONSTRAINT ck_agent_tokens_name CHECK (name <> '')
);
CREATE INDEX idx_agent_tokens_user ON agent_tokens (user_id, created_at);

-- The events an agent long-polls: the payload of the push it replaces, kept until expires_at.
-- seq is per user and gap-free: appends serialize on the user's agent_users row.
CREATE TABLE agent_events (
    user_id uuid NOT NULL,
    seq bigint NOT NULL,
    dedup_key varchar(100) NOT NULL,
    type varchar(64) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT pk_agent_events PRIMARY KEY (user_id, seq),
    CONSTRAINT fk_agent_events_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_agent_events_dedup UNIQUE (user_id, dedup_key),
    CONSTRAINT ck_agent_events_seq CHECK (seq > 0)
);
CREATE INDEX idx_agent_events_expires ON agent_events (expires_at);
