-- Device pairing on the web login table: one code space for every kind. Keyed kinds store only a commitment to the
-- target's key and secret, and the HPKE ciphertext until its single claim; never a key, secret or ITMO token.
ALTER TABLE web_login_challenges
    ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'WEB_SESSION',
    ADD COLUMN key_commitment bytea,
    ADD COLUMN target_name varchar(60),
    ADD COLUMN requested_by_version varchar(40),
    ADD COLUMN agent_token_id uuid,
    ADD COLUMN opened_by uuid,
    ADD COLUMN opened_at timestamp(6) with time zone,
    ADD COLUMN scopes varchar(100),
    ADD COLUMN sealed_bundle bytea,
    ADD CONSTRAINT fk_web_login_challenges_agent_token FOREIGN KEY (agent_token_id) REFERENCES agent_tokens (id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_web_login_challenges_opener FOREIGN KEY (opened_by) REFERENCES users (id) ON DELETE CASCADE,
    ADD CONSTRAINT ck_web_login_challenges_kind CHECK (kind IN ('WEB_SESSION', 'AGENT', 'AGENT_SESSION', 'WEB_ITMO')),
    ADD CONSTRAINT ck_web_login_challenges_commitment CHECK (
        (kind = 'WEB_SESSION') = (key_commitment IS NULL) AND (key_commitment IS NULL OR octet_length(key_commitment) = 32)
    ),
    ADD CONSTRAINT ck_web_login_challenges_agent CHECK ((kind = 'AGENT_SESSION') = (agent_token_id IS NOT NULL)),
    ADD CONSTRAINT ck_web_login_challenges_bundle CHECK (sealed_bundle IS NULL OR octet_length(sealed_bundle) BETWEEN 48 AND 16384),
    ADD CONSTRAINT ck_web_login_challenges_opened CHECK ((opened_by IS NULL) = (opened_at IS NULL));

-- A target's answer may now be a refusal by the phone.
ALTER TABLE web_login_challenges DROP CONSTRAINT ck_web_login_challenges_status;
ALTER TABLE web_login_challenges ADD CONSTRAINT ck_web_login_challenges_status
    CHECK (status IN ('PENDING', 'APPROVED', 'CLAIMED', 'EXPIRED', 'REJECTED'));

CREATE INDEX idx_web_login_challenges_agent_token ON web_login_challenges (agent_token_id) WHERE agent_token_id IS NOT NULL;
