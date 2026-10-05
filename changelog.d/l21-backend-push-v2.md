# Push notifications

- Android FCM messages carry `android.priority = high` and a TTL until they stop
  being actionable: sport pushes expire at the entry's eligibility deadline
  (matched lesson end for auto, one hour before start for free, lesson end for
  force free), friendship events 12 hours after `occurredAt`; the TTL is
  clamped to zero and to FCM's 28-day maximum. Messages stay data-only and the
  `data` and `recipient_isu` keys and every FCM fixture are unchanged.
