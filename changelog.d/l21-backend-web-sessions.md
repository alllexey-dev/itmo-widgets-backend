# Web login

- Web sessions (`iw_session`) now end after 14 days without requests or 60 days
  after sign-in instead of 2 and 12 hours; the cookie's `Max-Age` is 5184000.
  Both limits are settings, `itmowidgets.web-session.idle-timeout`
  (`WEB_SESSION_IDLE_TIMEOUT`) and `itmowidgets.web-session.max-lifetime`
  (`WEB_SESSION_MAX_LIFETIME`); ended sessions are deleted 90 days after expiry
  instead of 30. `web_sessions.last_seen_at` is written at most every 5 minutes.
  No migration.
