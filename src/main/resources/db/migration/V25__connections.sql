-- Connected clients (docs/contracts/connections.md): a self-hosted agent, an MCP server or a browser connects
-- through a phone-approved pairing and gets its own connection token (only its SHA-256 is stored). An agent or MCP
-- also gets its own ITMO.ID session, signed in on the phone; Backend keeps only that session's sid and end, never a
-- token. Replaces the alpha's agent tokens, pairing on web_login_challenges and web sessions, which V26 drops.

-- Enrollment: set when the user turns the services on in the app. Every user so far did.
ALTER TABLE users
    ADD COLUMN enrolled_at timestamp(6) with time zone,
    ADD COLUMN consent_version integer;
UPDATE users SET enrolled_at = created_at;

-- One row per connection. token_hash is null until the client claims its approval. itmo_sid is the client's own
-- ITMO.ID session (agent and mcp); a revoked row keeps it until the phone ends it (itmo_ended_at) or it expires.
CREATE TABLE connections (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    client varchar(8) NOT NULL,
    name varchar(60) NOT NULL,
    version varchar(40),
    platform varchar(16),
    scopes varchar(100) NOT NULL,
    token_hash char(64),
    created_at timestamp(6) with time zone NOT NULL,
    approved_at timestamp(6) with time zone NOT NULL,
    last_used_at timestamp(6) with time zone,
    last_poll_at timestamp(6) with time zone,
    revoked_at timestamp(6) with time zone,
    itmo_sid varchar(64),
    itmo_expires_at timestamp(6) with time zone,
    itmo_status varchar(8) NOT NULL DEFAULT 'NONE',
    itmo_ended_at timestamp(6) with time zone,
    CONSTRAINT fk_connections_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_connections_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_connections_client CHECK (client IN ('AGENT', 'MCP', 'WEB')),
    CONSTRAINT ck_connections_name CHECK (name <> ''),
    CONSTRAINT ck_connections_scopes CHECK (scopes <> ''),
    CONSTRAINT ck_connections_itmo_status CHECK (itmo_status IN ('OK', 'CLOSED', 'NONE')),
    CONSTRAINT ck_connections_itmo CHECK ((itmo_sid IS NULL) OR (itmo_expires_at IS NOT NULL))
);
CREATE INDEX idx_connections_user ON connections (user_id, created_at);
CREATE INDEX idx_connections_itmo_sid ON connections (itmo_sid) WHERE itmo_sid IS NOT NULL;

-- A pairing: the client asks (CONNECT) or a connection asks again (RENEW), the phone opens and approves it, the client
-- claims it once with its claim secret (SHA-256 stored). key_commitment is SHA-256 over the client's key and pairing
-- secret, never the key; sealed_bundle is the HPKE ciphertext for the client until the claim.
CREATE TABLE pair_challenges (
    id uuid PRIMARY KEY,
    kind varchar(8) NOT NULL,
    client varchar(8) NOT NULL,
    code varchar(8) NOT NULL,
    claim_secret_hash char(64) NOT NULL,
    key_commitment bytea NOT NULL,
    name varchar(60) NOT NULL,
    version varchar(40),
    platform varchar(16),
    scopes varchar(100) NOT NULL,
    status varchar(16) NOT NULL,
    client_ip varchar(64) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    opened_by uuid,
    opened_at timestamp(6) with time zone,
    approved_at timestamp(6) with time zone,
    connection_id uuid,
    sealed_bundle bytea,
    CONSTRAINT fk_pair_challenges_opener FOREIGN KEY (opened_by) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_pair_challenges_connection FOREIGN KEY (connection_id) REFERENCES connections (id) ON DELETE CASCADE,
    CONSTRAINT ck_pair_challenges_kind CHECK (kind IN ('CONNECT', 'RENEW')),
    CONSTRAINT ck_pair_challenges_client CHECK (client IN ('AGENT', 'MCP', 'WEB')),
    CONSTRAINT ck_pair_challenges_status CHECK (status IN ('PENDING', 'APPROVED', 'CLAIMED', 'EXPIRED')),
    CONSTRAINT ck_pair_challenges_commitment CHECK (octet_length(key_commitment) = 32),
    CONSTRAINT ck_pair_challenges_bundle CHECK (sealed_bundle IS NULL OR octet_length(sealed_bundle) BETWEEN 48 AND 16384),
    CONSTRAINT ck_pair_challenges_opened CHECK ((opened_by IS NULL) = (opened_at IS NULL)),
    CONSTRAINT ck_pair_challenges_renew CHECK (kind = 'CONNECT' OR connection_id IS NOT NULL),
    CONSTRAINT ck_pair_challenges_window CHECK (expires_at > created_at)
);
-- Only a pending code must be unique; used and expired codes may repeat.
CREATE UNIQUE INDEX uq_pair_challenges_pending_code ON pair_challenges (code) WHERE status = 'PENDING';
CREATE INDEX idx_pair_challenges_client ON pair_challenges (client_ip, created_at);
CREATE INDEX idx_pair_challenges_connection ON pair_challenges (connection_id) WHERE connection_id IS NOT NULL;

-- Per user: the agent beta access an admin granted and the event sequence, the newest seq handed out, never reused.
CREATE TABLE client_users (
    user_id uuid PRIMARY KEY,
    agent_access_granted_at timestamp(6) with time zone,
    last_event_seq bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_client_users_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_client_users_seq CHECK (last_event_seq >= 0)
);
-- The beta grants carry over; the alpha's tokens and events do not.
INSERT INTO client_users (user_id, agent_access_granted_at)
SELECT user_id, access_granted_at FROM agent_users WHERE access_granted_at IS NOT NULL;

-- The events an agent long-polls: the payload of the push it replaces, kept until expires_at. seq is per user and
-- gap-free: appends serialize on the user's client_users row.
CREATE TABLE client_events (
    user_id uuid NOT NULL,
    seq bigint NOT NULL,
    dedup_key varchar(100) NOT NULL,
    type varchar(64) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT pk_client_events PRIMARY KEY (user_id, seq),
    CONSTRAINT fk_client_events_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_client_events_dedup UNIQUE (user_id, dedup_key),
    CONSTRAINT ck_client_events_seq CHECK (seq > 0)
);
CREATE INDEX idx_client_events_expires ON client_events (expires_at);

-- An app install's own ITMO.ID session, from its ID-token sync, and MCP servers as a client kind.
ALTER TABLE client_instances ADD COLUMN itmo_sid varchar(64);
ALTER TABLE client_instances DROP CONSTRAINT ck_client_instances_kind;
ALTER TABLE client_instances ADD CONSTRAINT ck_client_instances_kind CHECK (kind IN ('ANDROID', 'IOS', 'WEB', 'AGENT', 'MCP'));

-- The install a device registration came from (X-Client), which joins its name and push state to its instance row.
ALTER TABLE devices ADD COLUMN install_id uuid;
CREATE INDEX idx_devices_install ON devices (user_id, install_id) WHERE install_id IS NOT NULL;
