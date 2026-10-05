# Authentication
- A protected route called without valid credentials (no bearer, an invalid or
  expired bearer, no or an expired web session) answers 401 with
  `ApiResponse{success: false, error: {code: "unauthorized"}}` and
  `WWW-Authenticate: Bearer` instead of 403 with an empty body. Privacy, role,
  `permission_denied`, `access_denied`, `restricted` and `csrf` denials stay 403.
  New golden fixture `unauthorized` (`errors/unauthorized.json`, kind `error`,
  `minCore` `1.8.0`).
- The JWT filter treats only token and key-set failures as an anonymous request;
  a database failure while resolving a verified caller is 500
  `internal_server_error`.
- A known caller is resolved with one read (user id joined with its settings
  row); registration and its insert-ignores run only on a miss.
- The ITMO.ID client (`azp`) of every access token is counted against
  `id.itmo.allowed-clients` (`student-personal-cabinet`,
  `student-personal-cabinet-dev`); `id.itmo.azp-mode=log` accepts all and logs
  `azp counts: {client: n}` once an hour. `enforce` rejects other clients with
  401 and stays off in v2.3.
