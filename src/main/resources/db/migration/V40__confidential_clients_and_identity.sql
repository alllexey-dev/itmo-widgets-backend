-- Client platform, step 2 (docs/contracts/connections.md#confidential-clients, ITMO.Widgets ADR 0040): confidential
-- client apps prove themselves with an Ed25519 key of their registry entry, the phone may send a client back to its
-- returnUrl after a same-device approval, and a connection may be granted identity (a Backend-signed JWT with the
-- user's ISU and name, handed over once at the claim) with or without any Widgets scope.

-- Where the phone sends the user after a same-device approval; https, from the registry only.
ALTER TABLE connection_clients
    ADD COLUMN return_url text,
    ADD CONSTRAINT ck_connection_clients_return_url
        CHECK (return_url IS NULL OR (char_length(return_url) <= 200 AND return_url LIKE 'https://%'));

-- The public keys a confidential client signs its assertions with, several at once for rotation. public_key is
-- base64url without padding of the raw 32-byte Ed25519 key; a public key is not a secret.
CREATE TABLE connection_client_keys (
    client_id text NOT NULL,
    kid text NOT NULL,
    public_key text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT pk_connection_client_keys PRIMARY KEY (client_id, kid),
    CONSTRAINT fk_connection_client_keys_client FOREIGN KEY (client_id) REFERENCES connection_clients (id) ON DELETE CASCADE,
    CONSTRAINT ck_connection_client_keys_kid CHECK (kid ~ '^[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT ck_connection_client_keys_public_key CHECK (public_key ~ '^[A-Za-z0-9_-]{43}$')
);

-- The ids of the client assertions already used, so none is replayed; rows past expires_at go with the pairing
-- expiry job.
CREATE TABLE connection_client_assertions (
    client_id text NOT NULL,
    jti text NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT pk_connection_client_assertions PRIMARY KEY (client_id, jti),
    CONSTRAINT fk_connection_client_assertions_client FOREIGN KEY (client_id) REFERENCES connection_clients (id) ON DELETE CASCADE,
    CONSTRAINT ck_connection_client_assertions_jti CHECK (char_length(jti) BETWEEN 1 AND 128)
);
CREATE INDEX idx_connection_client_assertions_expires ON connection_client_assertions (expires_at);

-- identity is a grant id of its own, next to widgets:<scope> and itmo:<...>.
ALTER TABLE connection_client_grants DROP CONSTRAINT ck_connection_client_grants_id;
ALTER TABLE connection_client_grants ADD CONSTRAINT ck_connection_client_grants_id
    CHECK (grant_id = 'identity' OR grant_id ~ '^(widgets|itmo):[a-z0-9:-]{1,100}$');

-- A connection granted identity only holds no Widgets scope: scopes may be empty now. The previous image reads an
-- empty value as no scope.
ALTER TABLE connections DROP CONSTRAINT ck_connections_scopes;
ALTER TABLE connections ADD COLUMN identity boolean NOT NULL DEFAULT false;
