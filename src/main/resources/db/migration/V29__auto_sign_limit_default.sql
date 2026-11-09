-- The monthly auto-sign limit is global now: the app_settings key sport.auto_sign_limit, default 10, edited by admins
-- (docs/contracts/sport-automation.md). user_settings.auto_sign_limit is retired and no longer read, but stays with
-- its NOT NULL and check: images before V29 read it, so a rollback stays image-only. Backend inserts settings rows
-- with the SQL default, so the default follows the global one and users created after V29 get 10 there too.
--
-- Expand-only, no backfill: existing rows keep their values (production was set to 10 by hand on 2026-11-09).
ALTER TABLE user_settings ALTER COLUMN auto_sign_limit SET DEFAULT 10;
