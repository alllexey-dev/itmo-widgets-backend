# Accounts

- `DELETE /api/users/me` deletes the caller's account through
  `AccountDeletionService` and returns `ApiResponse<String>`. It takes only a
  sign-in at most `itmowidgets.account.recent-auth` old (`ACCOUNT_RECENT_AUTH`,
  default `10m`): the bearer's ITMO.ID `auth_time` or the creation of the web
  session. Otherwise 403 with the new error code `recent_sign_in_required`;
  anonymous 401, a web session without `X-Web-Request: 1` 403 `csrf`. Fixture
  `deleteMyAccount`, `docs/openapi.json` (`user_deleteMyAccount`, error code).
