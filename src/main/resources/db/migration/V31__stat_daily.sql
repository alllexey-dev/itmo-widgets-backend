-- Daily counters (docs/contracts/server-statistics.md): what Backend sees happen and does not store otherwise, counted
-- in memory and added here once a minute and on shutdown by an additive upsert. A row is a Europe/Moscow day, a metric
-- name, two short labels from code (dim2 is empty where a metric has one label) and a number. It holds no user,
-- instance, IP, token or path, so it is not personal data: account deletion does not touch it, and rows are kept
-- indefinitely (a few hundred a day).
-- Expand-only: a new table; the previous image ignores it, so a rollback stays image-only.
CREATE TABLE stat_daily (
    day date NOT NULL,
    metric varchar(32) NOT NULL,
    dim1 varchar(64) NOT NULL,
    dim2 varchar(64) NOT NULL,
    value bigint NOT NULL,
    CONSTRAINT pk_stat_daily PRIMARY KEY (day, metric, dim1, dim2),
    CONSTRAINT ck_stat_daily_value CHECK (value >= 0)
);
