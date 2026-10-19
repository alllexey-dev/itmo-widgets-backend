-- Rotating refresh tokens for web sessions. A row stays one session (one connection) for its whole life: the access
-- token in token_hash lives until access_expires_at, the refresh token rotates both, and the previous refresh hash
-- is kept so a concurrent or lost refresh is told from a replayed one. Only SHA-256 hashes are stored.
-- Expand-only: nullable columns, no backfill. Existing rows have no access expiry and no refresh token, so their
-- cookie keeps working until expires_at as before; the previous image ignores the columns, a rollback is image-only.
ALTER TABLE web_sessions
    ADD COLUMN access_expires_at timestamp(6) with time zone,
    ADD COLUMN refresh_hash char(64),
    ADD COLUMN previous_refresh_hash char(64),
    ADD COLUMN rotated_at timestamp(6) with time zone;

CREATE UNIQUE INDEX uq_web_sessions_refresh_hash ON web_sessions (refresh_hash);
CREATE UNIQUE INDEX uq_web_sessions_previous_refresh_hash ON web_sessions (previous_refresh_hash);
