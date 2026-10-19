# Web login and sessions

Wire changes follow the [compatibility rule](compatibility.md).

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
   browser (the cookie is ignored there, so the request is anonymous: 401).
3. The browser polls `GET /api/web/auth/challenges/{id}` (anonymous) with the
   header `X-Poll-Secret: <pollSecret>` about every 2 seconds and gets
   `{status}`: `PENDING`, `APPROVED` or `EXPIRED`. Exactly one poll returns
   `APPROVED` together with both `Set-Cookie` headers; every later poll returns `EXPIRED`.
   An approval made in the code's last moment may still be claimed for one
   minute after `expiresAt`. A wrong poll secret is 404, the same as an unknown
   challenge; the secret is compared by hash in constant time.

Only the SHA-256 of the poll secret is stored. One client address may hold at
most 10 unapproved (pending or expired) challenges per 10 minutes; the eleventh
is 429 `rate_limited`. The address is the proxy's `X-Real-IP` when the direct
peer is a private or loopback address (the Caddy edge), otherwise the peer itself;
`X-Forwarded-For` is ignored. A scheduler expires pending codes every minute and
deletes challenges older than a day.

## Session and cookies

- A session has an access token and a refresh token, each 32 random bytes; the
  database keeps only their SHA-256 (`web_sessions.token_hash`,
  `web_sessions.refresh_hash`).
- Cookies, both `Secure; HttpOnly; SameSite=Strict`:
  - `iw_session=<access token>; Path=/api; Max-Age=3600`: authenticates every
    `/api/**` request; the token is accepted for one hour
    (`web_sessions.access_expires_at`).
  - `iw_refresh=<refresh token>; Path=/api/web/auth/refresh; Max-Age=2592000`:
    sent only to the refresh route. `Max-Age` is the idle limit; every refresh
    sets the cookie again.
- `POST /api/web/auth/refresh` (anonymous route, needs `X-Web-Request: 1`,
  otherwise 403 `csrf`) authenticates by `iw_refresh` alone and:
  - for the current refresh token: issues a new access and a new refresh token
    in the same session (the connection keeps its id), sets both cookies and
    answers 200 `ApiResponse<Unit>`;
  - for the previous refresh token within 2 minutes of its rotation (another
    tab or a lost response already rotated it): answers 200 without cookies and
    changes nothing;
  - for the previous refresh token later (a replay): revokes the session;
  - for a replayed, unknown, revoked, idle or expired token, or without the
    cookie: 401 `unauthorized` and clears both cookies.
  Web refreshes when a request answers 401 `unauthorized` and repeats the
  request once; refreshes are serialized across tabs.
- A session ends after 30 days without requests or refreshes; there is no
  absolute limit. `web_sessions.expires_at` moves 30 days ahead with every
  refresh. `POST /api/web/auth/logout` revokes it and
  clears both cookies; so does revoking it in the
  [connections list](pairing.md#connections). Ended sessions are deleted 90 days
  after expiry; until then they count in the admin dashboard.
- Sessions signed in before refresh tokens (`access_expires_at` null) keep their
  60-day `iw_session` cookie until their own limits and have no refresh token:
  they sign in again when it ends.
- Use extends the idle window; `web_sessions.last_seen_at` is written at most
  once every 5 minutes and on every refresh, so it may lag the latest request by
  that much.
- Admin and moderation routes (`/api/admin/**`, `/api/moderation/**`) accept a
  session only up to 7 days after sign-in (`web_sessions.created_at`); use
  and refreshes do not extend this. An older session gets 401 `reauth_required`
  there, see [fresh sign-in for admin routes](#fresh-sign-in-for-admin-routes),
  and keeps working everywhere else.
- The limits are settings read at startup. A new idle limit or admin age
  applies to every session at once, a new access lifetime from a session's next
  refresh:

  | Property | Environment override | Default |
  |---|---|---|
  | `itmowidgets.web-session.idle-timeout` | `WEB_SESSION_IDLE_TIMEOUT` | `30d` |
  | `itmowidgets.web-session.admin-max-age` | `WEB_SESSION_ADMIN_MAX_AGE` | `7d` |
  | `itmowidgets.web-session.access-ttl` | `WEB_SESSION_ACCESS_TTL` | `1h` |

  The admin age must be positive, the access lifetime positive and at most the
  idle limit, otherwise startup fails. `WEB_SESSION_MAX_LIFETIME` is no longer
  read.
  The tracked Compose file does not forward these variables; add them through
  a reviewed override.
- `GET /api/web/auth/me` → `{isu, name, pictureUrl, groups, roles}`; `groups` are
  the stored study groups ([friendships](friendships.md#study-groups)), `roles`
  are `MODERATOR`/`ADMIN` names.
  `GET /api/users/me/roles` returns the same role list for the app.

## Authentication order and CSRF

A request with `Authorization: Bearer …` is authenticated by the ITMO.ID token
exactly as before, and an invalid bearer is never replaced by the cookie: the
request stays anonymous and a protected route answers 401 `unauthorized`, as it
does for an expired or missing session.
Without a bearer, `WebSessionFilter` authenticates the `iw_session` cookie, so a
web session works on every authenticated `/api/**` route, including the admin
API within the admin age (see below). Requests authenticated by the cookie
with a method other than GET or HEAD must carry `X-Web-Request: 1`, otherwise
they are rejected with 403 `csrf` before any controller runs; this is on top of
`SameSite=Strict`. The cookie is ignored on `/api/users/me/web-login/**` and
`/api/web/auth/challenges/**`.

## Fresh sign-in for admin routes

Without a bearer, a request to `/api/admin/**` or `/api/moderation/**` whose
web session was signed in more than `admin-max-age` (7 days) ago is answered
401 `reauth_required` with `WWW-Authenticate: Bearer` before any controller or
role check runs, whatever the caller's roles; this check comes before the
`X-Web-Request` one. The session is not revoked: student routes and
`GET /api/web/auth/me` keep accepting it until its own limits end it. Web tells
`reauth_required` from `unauthorized` (a lost session) and offers to sign in
again through the phone-approved login, which issues a new session with a new
`created_at`. Requests with the app's ITMO.ID bearer token never reach this
check.

Anonymous routes are only `POST /api/web/auth/challenges`,
`GET /api/web/auth/challenges/{id}`, `POST /api/web/auth/refresh`, the pairing routes
`POST /api/pair/challenges` and `GET /api/pair/challenges/{id}`
([pairing](pairing.md)) and the existing `/api/app/**`. The cookie is also
ignored on `/api/users/me/pair/**` and `/api/pair/challenges/**`.

The app may also open and approve a web login code through pairing
(`/api/users/me/pair/{code}/open` and `.../approve`, kind `WEB_SESSION`); the
browser claims it on the same poll route. Every claimed web login pushes
`DEVICE_CONNECTED_PAYLOAD` to the user's devices, and the session appears in
the [connections list](pairing.md#connections), where it can be revoked.

## Later: sign-in with an ITMO.ID token

Student sections of the web version are not part of this release. They will
sign in with an ITMO.ID token kept in the browser, which also authorizes our
services as the bearer token Backend already accepts. Backend does not proxy
MyITMO for the browser.
