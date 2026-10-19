-- Client statistics (ITMO.Widgets ADR 0034). One row per client instance of a signed-in user, keyed by the app's
-- install id, the web session id or the agent token id, with what its X-Client header last said. Rows go 180 days
-- after last_seen_at and with the account (ON DELETE CASCADE; the account-deletion runbook deletes them too).
-- client_daily keeps anonymous daily totals per build group for the adoption history; it has no user or instance ids.
-- Expand-only: new tables; the previous image ignores them, so a rollback stays image-only.
CREATE TABLE client_instances (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    kind varchar(16) NOT NULL,
    instance_id uuid NOT NULL,
    version varchar(64) NOT NULL,
    build varchar(64),
    channel varchar(16),
    source varchar(64),
    commit_sha varchar(64),
    base varchar(64),
    os varchar(64),
    browser varchar(64),
    custom boolean NOT NULL,
    first_seen_at timestamp(6) with time zone NOT NULL,
    last_seen_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT fk_client_instances_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_client_instances_instance UNIQUE (kind, instance_id),
    CONSTRAINT ck_client_instances_kind CHECK (kind IN ('ANDROID', 'IOS', 'WEB', 'AGENT')),
    CONSTRAINT ck_client_instances_seen CHECK (last_seen_at >= first_seen_at)
);
CREATE INDEX idx_client_instances_user ON client_instances (user_id);
CREATE INDEX idx_client_instances_last_seen ON client_instances (last_seen_at);

-- version is the group version: an official build's own, a custom build's upstream base. Empty strings stand for
-- an absent channel or source so the key has no nulls.
CREATE TABLE client_daily (
    day date NOT NULL,
    kind varchar(16) NOT NULL,
    custom boolean NOT NULL,
    version varchar(64) NOT NULL,
    channel varchar(16) NOT NULL,
    source varchar(64) NOT NULL,
    active_1d integer NOT NULL,
    active_7d integer NOT NULL,
    active_30d integer NOT NULL,
    CONSTRAINT pk_client_daily PRIMARY KEY (day, kind, custom, version, channel, source)
);
