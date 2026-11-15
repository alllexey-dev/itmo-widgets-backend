-- Who is active (feature/activity, docs/contracts/clients.md#people). people_daily keeps the nightly count of
-- distinct people by their last activity in the last 1, 7 and 30 days: counts only, kept indefinitely.
-- user_active_weeks keeps one row per account and Europe/Moscow calendar week (week_start is its Monday) in which the
-- account made an authenticated request. It is personal data, kept while the account exists and deleted with it
-- (ON DELETE CASCADE; the account-deletion runbook deletes the rows too).
-- Expand-only: new tables; the previous image ignores them, so a rollback stays image-only.
CREATE TABLE people_daily (
    day date PRIMARY KEY,
    active_1d integer NOT NULL,
    active_7d integer NOT NULL,
    active_30d integer NOT NULL,
    CONSTRAINT ck_people_daily_windows CHECK (0 <= active_1d AND active_1d <= active_7d AND active_7d <= active_30d)
);

CREATE TABLE user_active_weeks (
    user_id uuid NOT NULL,
    week_start date NOT NULL,
    CONSTRAINT pk_user_active_weeks PRIMARY KEY (user_id, week_start),
    CONSTRAINT fk_user_active_weeks_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_active_weeks_monday CHECK (EXTRACT(ISODOW FROM week_start) = 1)
);
-- The recorder reads one week's accounts, the cohorts start at the first collected week.
CREATE INDEX idx_user_active_weeks_week ON user_active_weeks (week_start);
