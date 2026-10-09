# Web login

- Admin and moderation routes (`/api/admin/**`, `/api/moderation/**`) accept a
  web session only up to 12 hours after sign-in; an older one gets 401 with the
  new error code `reauth_required` (fixture `errors/reauth_required.json`) and
  stays valid on every other route. The limit is the setting
  `itmowidgets.web-session.admin-max-age` (`WEB_SESSION_ADMIN_MAX_AGE`), positive
  and at most the session's max lifetime. The ITMO.ID bearer path is unchanged.
  No migration.
