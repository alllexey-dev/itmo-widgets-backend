# My ITMO

- Backend talks to My ITMO through MyItmoApi 2.0.0 (`dev.alllexey:my-itmo-api-kmp`,
  Ktor with OkHttp) instead of 1.8.2; no route, fixture or schema changes.
  `MyItmoService` is the client's `TokenStorage` over `service_credentials`; a
  refresh token seeded from `MY_ITMO_REFRESH_TOKEN` or replaced by an admin is
  refreshed before the first My ITMO request. A failed refresh leaves the stored
  refresh token as it is.
- A sport lesson whose `date` or `date_end` is missing is skipped as before;
  other missing lesson fields now read as MyItmoApi's defaults (`0`, empty text),
  and a `null` row fails the whole answer as `MAPPING` instead of being skipped
  alone.
