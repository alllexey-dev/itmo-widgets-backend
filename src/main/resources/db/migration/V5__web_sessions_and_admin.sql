-- Phone-approved web login, browser sessions, admin-editable settings and the admin audit.
ALTER TABLE user_roles DROP CONSTRAINT ck_user_roles_role;
ALTER TABLE user_roles ADD CONSTRAINT ck_user_roles_role CHECK (role IN ('MODERATOR', 'ADMIN'));

-- A browser asks, a signed-in app approves, the same browser claims one session with its poll secret.
CREATE TABLE web_login_challenges (
    id uuid PRIMARY KEY,
    code varchar(8) NOT NULL,
    poll_secret_hash char(64) NOT NULL,
    status varchar(16) NOT NULL,
    user_agent varchar(300),
    client_ip varchar(64) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    approved_by uuid,
    approved_at timestamp(6) with time zone,
    CONSTRAINT fk_web_login_challenges_approver FOREIGN KEY (approved_by) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_web_login_challenges_status CHECK (status IN ('PENDING', 'APPROVED', 'CLAIMED', 'EXPIRED')),
    CONSTRAINT ck_web_login_challenges_window CHECK (expires_at > created_at),
    CONSTRAINT ck_web_login_challenges_approval CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
    CONSTRAINT ck_web_login_challenges_approved CHECK (status NOT IN ('APPROVED', 'CLAIMED') OR approved_by IS NOT NULL)
);
-- Only a pending code must be unique; used and expired codes may repeat.
CREATE UNIQUE INDEX uq_web_login_challenges_pending_code ON web_login_challenges (code) WHERE status = 'PENDING';
CREATE INDEX idx_web_login_challenges_client ON web_login_challenges (client_ip, created_at);

-- Only the SHA-256 of the cookie token is stored; revoked and expired rows remain for statistics until retention.
CREATE TABLE web_sessions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    token_hash char(64) NOT NULL,
    user_agent varchar(300),
    created_at timestamp(6) with time zone NOT NULL,
    last_seen_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    revoked_at timestamp(6) with time zone,
    CONSTRAINT fk_web_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_web_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_web_sessions_window CHECK (expires_at > created_at)
);
CREATE INDEX idx_web_sessions_user ON web_sessions (user_id, created_at);
CREATE INDEX idx_web_sessions_expires ON web_sessions (expires_at);

-- Application settings an admin changes at runtime; absent keys fall back to the environment.
CREATE TABLE app_settings (
    key varchar(64) PRIMARY KEY,
    value varchar(500) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    updated_by uuid,
    CONSTRAINT fk_app_settings_admin FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE SET NULL
);

-- Insert-only record of admin changes to roles and settings.
CREATE TABLE admin_audit (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    action varchar(40) NOT NULL,
    target varchar(200) NOT NULL,
    details varchar(1000),
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT fk_admin_audit_actor FOREIGN KEY (actor_id) REFERENCES users (id)
);
CREATE INDEX idx_admin_audit_created ON admin_audit (created_at);
