-- Table names say which feature a table belongs to and what a row is (docs/ops/database.md#tables). Only tables are
-- renamed: columns, constraints, indexes and identity sequences keep their names.
--   lessons, user_subject_flows       -> schedule_lessons, schedule_flows: the schedule a user uploaded, next to the
--                                        shared sport_lessons catalog.
--   isu_potok*                        -> isu_flow*: a potok is the schedule flow of flow_id.
--   groups, user_groups               -> study_groups, user_study_groups.
--   user_sport_lessons                -> sport_bookings: the bookings My ITMO confirmed.
--   sport_section_subscriptions,
--   sport_section_occurrences         -> sport_slot_subscriptions, sport_slot_occurrences: sport_sections is the
--                                        dictionary of sport sections, these are a user's repeating slot.
--   devices                           -> push_devices: push registrations of the app.
--   client_instances                  -> client_installs: the installs the X-Client header reports.
--   client_users, client_events,
--   pair_challenges                   -> connection_users, connection_events, connection_pair_challenges: they belong
--                                        to connected clients, not to client statistics.
--   stat_daily, people_daily,
--   client_daily, user_active_weeks   -> stats_daily, stats_people_daily, stats_clients_daily, stats_user_active_weeks.
-- Rollback stays image-only: each old name remains as a view of its table. A view over one table is updatable, so the
-- previous image reads, writes (INSERT ... ON CONFLICT included) and locks rows through it, and Hibernate validates
-- it as a table. The views are dropped in the release after the one that stopped using the old names.
ALTER TABLE lessons RENAME TO schedule_lessons;
ALTER TABLE user_subject_flows RENAME TO schedule_flows;
ALTER TABLE isu_potoks RENAME TO isu_flows;
ALTER TABLE isu_potok_members RENAME TO isu_flow_members;
ALTER TABLE isu_potok_teachers RENAME TO isu_flow_teachers;
ALTER TABLE groups RENAME TO study_groups;
ALTER TABLE user_groups RENAME TO user_study_groups;
ALTER TABLE user_sport_lessons RENAME TO sport_bookings;
ALTER TABLE sport_section_subscriptions RENAME TO sport_slot_subscriptions;
ALTER TABLE sport_section_occurrences RENAME TO sport_slot_occurrences;
ALTER TABLE devices RENAME TO push_devices;
ALTER TABLE client_instances RENAME TO client_installs;
ALTER TABLE client_users RENAME TO connection_users;
ALTER TABLE client_events RENAME TO connection_events;
ALTER TABLE pair_challenges RENAME TO connection_pair_challenges;
ALTER TABLE stat_daily RENAME TO stats_daily;
ALTER TABLE people_daily RENAME TO stats_people_daily;
ALTER TABLE client_daily RENAME TO stats_clients_daily;
ALTER TABLE user_active_weeks RENAME TO stats_user_active_weeks;

CREATE VIEW lessons AS SELECT * FROM schedule_lessons;
CREATE VIEW user_subject_flows AS SELECT * FROM schedule_flows;
CREATE VIEW isu_potoks AS SELECT * FROM isu_flows;
CREATE VIEW isu_potok_members AS SELECT * FROM isu_flow_members;
CREATE VIEW isu_potok_teachers AS SELECT * FROM isu_flow_teachers;
CREATE VIEW groups AS SELECT * FROM study_groups;
CREATE VIEW user_groups AS SELECT * FROM user_study_groups;
CREATE VIEW user_sport_lessons AS SELECT * FROM sport_bookings;
CREATE VIEW sport_section_subscriptions AS SELECT * FROM sport_slot_subscriptions;
CREATE VIEW sport_section_occurrences AS SELECT * FROM sport_slot_occurrences;
CREATE VIEW devices AS SELECT * FROM push_devices;
CREATE VIEW client_instances AS SELECT * FROM client_installs;
CREATE VIEW client_users AS SELECT * FROM connection_users;
CREATE VIEW client_events AS SELECT * FROM connection_events;
CREATE VIEW pair_challenges AS SELECT * FROM connection_pair_challenges;
CREATE VIEW stat_daily AS SELECT * FROM stats_daily;
CREATE VIEW people_daily AS SELECT * FROM stats_people_daily;
CREATE VIEW client_daily AS SELECT * FROM stats_clients_daily;
CREATE VIEW user_active_weeks AS SELECT * FROM stats_user_active_weeks;
