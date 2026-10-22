-- The alpha's agent tokens, events, pairing and web sessions, replaced by V25. They had no users. Not expand-only:
-- an image before V25 no longer starts against this schema (docs/ops/database.md#migrations).
DROP TABLE web_login_challenges;
DROP TABLE web_sessions;
DROP TABLE agent_events;
DROP TABLE agent_tokens;
DROP TABLE agent_users;

-- Statistics rows keyed by the dropped web sessions and agent tokens.
DELETE FROM client_instances WHERE kind IN ('WEB', 'AGENT');
