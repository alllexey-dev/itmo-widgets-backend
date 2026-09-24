# Web login and sessions

The web version lives at `https://<domain>/app/` and calls the same `/api/**`
on the same origin, so no CORS is configured. A browser signs in by approval
from a phone that is already signed in to the app; Backend then issues a web
session in an httpOnly cookie. Any user may sign in; what they see depends on
their roles ([admin API](admin.md)).

## Phone-approved login

1. The browser calls `POST /api/web/auth/challenges` (anonymous) and gets
   `{id, code, pollSecret, expiresAt}`. The code is 8 characters from
   `ABCDEFGHJKMNPQRSTUVWXYZ23456789`, lives 2 minutes and is single-use. The web
   shows it and a QR code with `https://<domain>/app/login?code=<code>`.
2. In the app the user scans the QR or types the code. The app calls
   `GET /api/users/me/web-login/{code}` → `WebLoginPreview`
   `{challengeId, userAgent, createdAt, expiresAt}` (404 `not_found` for an
   unknown, used or expired code) and, after the user confirms,
   `POST /api/users/me/web-login/{challengeId}/approve`. Both routes accept only
   the app's ITMO.ID bearer token: a web session can never approve another
   browser (403).
3. The browser polls `GET /api/web/auth/challenges/{id}` (anonymous) with the
   header `X-Poll-Secret: <pollSecret>` about every 2 seconds and gets
   `{status}`: `PENDING`, `APPROVED` or `EXPIRED`. Exactly one poll returns
   `APPROVED` together with `Set-Cookie`; every later poll returns `EXPIRED`.
   An approval made in the code's last moment may still be claimed for one
   minute after `expiresAt`. A wrong poll secret is 404, the same as an unknown
   challenge; the secret is compared by hash in constant time.

Only the SHA-256 of the poll secret is stored. One client address may hold at
most 10 unapproved (pending or expired) challenges per 10 minutes; the eleventh
is 429 `rate_limited`. The address is the proxy's `X-Real-IP` when the direct
peer is a private or loopback address (nginx-hub), otherwise the peer itself;
`X-Forwarded-For` is ignored. A scheduler expires pending codes every minute and
deletes challenges older than a day.

## Session and cookie

- The token is 32 random bytes; the database keeps only its SHA-256
  (`web_sessions.token_hash`).
- Cookie: `iw_session=<token>; Path=/api; Max-Age=43200; Secure; HttpOnly;
  SameSite=Strict`.
- A session ends after 2 hours without requests or 12 hours after sign-in,
  whichever comes first. `POST /api/web/auth/logout` revokes it and clears the
  cookie. Ended sessions are deleted 30 days after expiry; until then they count
  in the admin dashboard.
- `GET /api/web/auth/me` → `{isu, name, pictureUrl, groups, roles}`; `groups` are
  the current study groups, `roles` are `MODERATOR`/`ADMIN` names.
  `GET /api/users/me/roles` returns the same role list for the app.

## Authentication order and CSRF

A request with `Authorization: Bearer …` is authenticated by the ITMO.ID token
exactly as before, and an invalid bearer is never replaced by the cookie.
Without a bearer, `WebSessionFilter` authenticates the `iw_session` cookie, so a
web session works on every authenticated `/api/**` route, including the admin
API. Requests authenticated by the cookie with a method other than GET or HEAD
must carry `X-Web-Request: 1`, otherwise they are rejected with 403 `csrf`
before any controller runs; this is on top of `SameSite=Strict`. The cookie is
ignored on `/api/users/me/web-login/**` and `/api/web/auth/challenges/**`.

Anonymous routes are only `POST /api/web/auth/challenges`,
`GET /api/web/auth/challenges/{id}` and the existing `/api/app/**`.

## Later: sign-in with an ITMO.ID token

Student sections of the web version are not part of this release. They will
sign in with an ITMO.ID token kept in the browser, which also authorizes our
services as the bearer token Backend already accepts. Backend does not proxy
MyITMO for the browser.
