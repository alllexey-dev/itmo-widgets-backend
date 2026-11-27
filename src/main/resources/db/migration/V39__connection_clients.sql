-- The registry of connected client apps (docs/contracts/connections.md#registry, ITMO.Widgets ADR 0040): one row per
-- client app the owner registered, and the grants each one may request. Pairing reads it instead of the hard-coded
-- client types; the three types so far are its first rows. A connection and a pairing challenge name their client
-- and the ITMO grants the phone sealed for it, never an ITMO credential.
CREATE TABLE connection_clients (
    id text PRIMARY KEY,
    kind text NOT NULL,
    name text NOT NULL,
    homepage text,
    icon_url text,
    deployment text NOT NULL,
    auth text NOT NULL,
    status text NOT NULL,
    max_per_user integer,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ck_connection_clients_id CHECK (id ~ '^[a-z0-9][a-z0-9-]{0,63}$'),
    CONSTRAINT ck_connection_clients_kind CHECK (kind IN ('agent', 'mcp', 'web', 'app')),
    CONSTRAINT ck_connection_clients_name CHECK (char_length(name) BETWEEN 1 AND 60),
    CONSTRAINT ck_connection_clients_homepage CHECK (homepage IS NULL OR char_length(homepage) BETWEEN 1 AND 200),
    CONSTRAINT ck_connection_clients_icon_url CHECK (icon_url IS NULL OR char_length(icon_url) BETWEEN 1 AND 200),
    CONSTRAINT ck_connection_clients_deployment CHECK (deployment IN ('self_hosted', 'hosted')),
    CONSTRAINT ck_connection_clients_auth CHECK (auth IN ('public', 'confidential')),
    CONSTRAINT ck_connection_clients_status CHECK (status IN ('active', 'suspended')),
    CONSTRAINT ck_connection_clients_max_per_user CHECK (max_per_user IS NULL OR max_per_user > 0)
);

-- What a client may request, in the order the phone lists it. purpose is the owner's Russian text of what this client
-- does with the grant. The full ITMO.ID session cookie (itmo:sso) is only for clients on the user's own machine.
CREATE TABLE connection_client_grants (
    client_id text NOT NULL,
    grant_id text NOT NULL,
    required boolean NOT NULL,
    requested_by_default boolean NOT NULL,
    purpose text,
    position integer NOT NULL,
    CONSTRAINT pk_connection_client_grants PRIMARY KEY (client_id, grant_id),
    CONSTRAINT fk_connection_client_grants_client FOREIGN KEY (client_id) REFERENCES connection_clients (id) ON DELETE CASCADE,
    CONSTRAINT uq_connection_client_grants_position UNIQUE (client_id, position),
    CONSTRAINT ck_connection_client_grants_id CHECK (grant_id ~ '^(widgets|itmo):[a-z0-9:-]{1,100}$'),
    CONSTRAINT ck_connection_client_grants_purpose CHECK (purpose IS NULL OR char_length(purpose) BETWEEN 1 AND 200),
    CONSTRAINT ck_connection_client_grants_position CHECK (position >= 0)
);

INSERT INTO connection_clients (id, kind, name, homepage, icon_url, deployment, auth, status, max_per_user, created_at, updated_at)
VALUES ('itmo-widgets-agent', 'agent', 'ITMO.Widgets agent', NULL, NULL, 'self_hosted', 'public', 'active', NULL, now(), now()),
       ('itmo-mcp', 'mcp', 'ITMO MCP', NULL, NULL, 'self_hosted', 'public', 'active', NULL, now(), now()),
       ('web', 'web', 'ITMO.Widgets в браузере', NULL, NULL, 'hosted', 'public', 'active', NULL, now(), now());

INSERT INTO connection_client_grants (client_id, grant_id, required, requested_by_default, purpose, position)
VALUES ('itmo-widgets-agent', 'widgets:sport', false, true, NULL, 0),
       ('itmo-widgets-agent', 'widgets:friends:read', false, false, NULL, 1),
       ('itmo-widgets-agent', 'widgets:schedule:write', false, false, NULL, 2),
       ('itmo-widgets-agent', 'itmo:refresh:student-personal-cabinet', true, true, 'Запись на физкультуру через my.itmo', 3),
       ('itmo-mcp', 'widgets:reviews:read', false, true, NULL, 0),
       ('itmo-mcp', 'widgets:links:read', false, true, NULL, 1),
       ('itmo-mcp', 'widgets:sport:queue', false, false, NULL, 2),
       ('itmo-mcp', 'itmo:refresh:student-personal-cabinet', false, true, 'Расписание, зачётка, физкультура и заявки в my.itmo', 3),
       ('itmo-mcp', 'itmo:sso', false, false, 'Баллы БАРС и вход в другие сервисы ИТМО без повторной привязки', 4),
       ('web', 'widgets:user', true, true, NULL, 0);

-- A client app of a new kind (app) is a new client value too.
ALTER TABLE connections DROP CONSTRAINT ck_connections_client;
ALTER TABLE connections ADD CONSTRAINT ck_connections_client CHECK (client IN ('AGENT', 'MCP', 'WEB', 'APP'));
ALTER TABLE connection_pair_challenges DROP CONSTRAINT ck_pair_challenges_client;
ALTER TABLE connection_pair_challenges ADD CONSTRAINT ck_pair_challenges_client CHECK (client IN ('AGENT', 'MCP', 'WEB', 'APP'));

-- client_id stays nullable: the previous image still writes rows without it, and a null reads as the seeded client of
-- the row's kind. itmo_grants are the ITMO grants the phone sealed for the connection's current ITMO.ID session.
ALTER TABLE connections
    ADD COLUMN client_id text,
    ADD COLUMN itmo_grants text[] NOT NULL DEFAULT '{}',
    ADD CONSTRAINT fk_connections_client FOREIGN KEY (client_id) REFERENCES connection_clients (id);
UPDATE connections
SET client_id = CASE client WHEN 'AGENT' THEN 'itmo-widgets-agent' WHEN 'MCP' THEN 'itmo-mcp' WHEN 'WEB' THEN 'web' END;
UPDATE connections SET itmo_grants = '{itmo:refresh:student-personal-cabinet}' WHERE itmo_sid IS NOT NULL;
CREATE INDEX idx_connections_client ON connections (client_id) WHERE revoked_at IS NULL;

-- A challenge names its client, the grants it requested ([{id, required}] in registry order) and its protocol: 1 for
-- the released clients and apps, 2 for a challenge only an app that knows grants may open. Null requested_grants
-- (rows of the previous image) read as the legacy mapping the backfill below writes.
ALTER TABLE connection_pair_challenges
    ADD COLUMN client_id text,
    ADD COLUMN requested_grants jsonb,
    ADD COLUMN protocol smallint NOT NULL DEFAULT 1,
    ADD CONSTRAINT fk_pair_challenges_client FOREIGN KEY (client_id) REFERENCES connection_clients (id),
    ADD CONSTRAINT ck_pair_challenges_protocol CHECK (protocol IN (1, 2)),
    ADD CONSTRAINT ck_pair_challenges_requested_grants CHECK (requested_grants IS NULL OR jsonb_typeof(requested_grants) = 'array');
UPDATE connection_pair_challenges
SET client_id = CASE client WHEN 'AGENT' THEN 'itmo-widgets-agent' WHEN 'MCP' THEN 'itmo-mcp' WHEN 'WEB' THEN 'web' END;
UPDATE connection_pair_challenges c
SET requested_grants = COALESCE((
        SELECT jsonb_agg(jsonb_build_object('id', 'widgets:' || t.scope, 'required', COALESCE(g.required, false)) ORDER BY t.ord)
        FROM unnest(string_to_array(c.scopes, ',')) WITH ORDINALITY AS t(scope, ord)
        LEFT JOIN connection_client_grants g ON g.client_id = c.client_id AND g.grant_id = 'widgets:' || t.scope
        WHERE c.kind = 'CONNECT' AND t.scope <> ''
    ), '[]'::jsonb)
    || CASE WHEN c.client IN ('AGENT', 'MCP')
        THEN '[{"id": "itmo:refresh:student-personal-cabinet", "required": true}]'::jsonb
        ELSE '[]'::jsonb END;
