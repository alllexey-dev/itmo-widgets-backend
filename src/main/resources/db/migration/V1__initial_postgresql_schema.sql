-- Fresh v2.1 database only. No MariaDB data, legacy auth tokens or secrets are imported.
-- Once deployed, this file is immutable: evolve the schema with V2 and later migrations.

CREATE TABLE qualifications (
    code bigint PRIMARY KEY,
    name text NOT NULL
);

CREATE TABLE faculties (
    id bigint PRIMARY KEY,
    name text NOT NULL,
    short_name text NOT NULL
);

CREATE TABLE groups (
    id uuid PRIMARY KEY,
    name varchar(255) NOT NULL,
    course integer NOT NULL,
    qualification_id bigint NOT NULL,
    faculty_id bigint NOT NULL,
    CONSTRAINT fk_groups_qualification FOREIGN KEY (qualification_id) REFERENCES qualifications (code),
    CONSTRAINT fk_groups_faculty FOREIGN KEY (faculty_id) REFERENCES faculties (id)
);
CREATE INDEX idx_groups_name ON groups (name);
CREATE INDEX idx_groups_qualification ON groups (qualification_id);
CREATE INDEX idx_groups_faculty ON groups (faculty_id);

CREATE TABLE user_settings (
    id uuid PRIMARY KEY,
    auto_sign_limit integer NOT NULL DEFAULT 3,
    sport_sharing boolean NOT NULL DEFAULT true,
    schedule_sharing boolean NOT NULL DEFAULT true,
    -- Explicit NULL remains supported by the legacy boolean settings contract.
    sport_visibility varchar(16) DEFAULT 'FRIENDS',
    schedule_visibility varchar(16) DEFAULT 'FRIENDS',
    CONSTRAINT ck_settings_sport_visibility CHECK (sport_visibility IN ('ALL', 'FRIENDS', 'NOBODY')),
    CONSTRAINT ck_settings_schedule_visibility CHECK (schedule_visibility IN ('ALL', 'FRIENDS', 'NOBODY'))
);

CREATE TABLE users (
    id uuid PRIMARY KEY,
    isu integer NOT NULL,
    picture_url text,
    name text,
    created_at timestamp(6) with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settings_id uuid NOT NULL,
    CONSTRAINT uq_users_isu UNIQUE (isu),
    CONSTRAINT uq_users_settings UNIQUE (settings_id),
    CONSTRAINT fk_users_settings FOREIGN KEY (settings_id) REFERENCES user_settings (id)
);

CREATE TABLE user_groups (
    user_id uuid NOT NULL,
    group_id uuid NOT NULL,
    PRIMARY KEY (user_id, group_id),
    CONSTRAINT fk_user_groups_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_groups_group FOREIGN KEY (group_id) REFERENCES groups (id)
);
CREATE INDEX idx_user_groups_group ON user_groups (group_id);

CREATE TABLE devices (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    fcm_token varchar(255) NOT NULL,
    device_name varchar(255) NOT NULL,
    last_login timestamp(6) with time zone NOT NULL,
    CONSTRAINT uq_devices_fcm_token UNIQUE (fcm_token),
    CONSTRAINT fk_devices_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_devices_user ON devices (user_id);

CREATE TABLE friend_requests (
    id uuid PRIMARY KEY,
    from_user_id uuid NOT NULL,
    to_user_id uuid NOT NULL,
    status varchar(255) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    last_activated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT uq_friend_requests_direction UNIQUE (from_user_id, to_user_id),
    CONSTRAINT fk_friend_requests_from FOREIGN KEY (from_user_id) REFERENCES users (id),
    CONSTRAINT fk_friend_requests_to FOREIGN KEY (to_user_id) REFERENCES users (id),
    CONSTRAINT ck_friend_requests_status CHECK (status IN ('ACTIVE', 'CANCELLED'))
);
CREATE INDEX idx_friend_requests_incoming ON friend_requests (to_user_id, status);

-- Lessons are cached by official ISU, not a backend UUID. Do not invent a user FK.
CREATE TABLE lessons (
    id uuid PRIMARY KEY,
    user_isu integer NOT NULL,
    date date NOT NULL,
    pair_id bigint NOT NULL,
    subject_id bigint NOT NULL,
    subject_name text NOT NULL,
    teacher_isu bigint,
    teacher_fio text,
    start_time time(6) without time zone NOT NULL,
    end_time time(6) without time zone NOT NULL,
    type text NOT NULL,
    type_id integer NOT NULL,
    group_name text NOT NULL,
    flow_id bigint NOT NULL,
    flow_type_id integer NOT NULL,
    note text,
    room text,
    building text,
    building_id integer,
    main_building_id integer,
    format text NOT NULL,
    format_id integer NOT NULL,
    CONSTRAINT uniq_user_pair UNIQUE (user_isu, pair_id)
);
CREATE INDEX idx_user_date ON lessons (user_isu, date);
CREATE INDEX idx_pair_id ON lessons (pair_id);
-- uniq_user_pair also serves lookups by (user_isu, pair_id).

CREATE TABLE my_itmo_storage (
    id bigint PRIMARY KEY,
    refresh_token text,
    refresh_token_expires_at bigint NOT NULL,
    access_token text,
    access_token_expires_at bigint NOT NULL,
    id_token text
);

CREATE TABLE sport_buildings (
    id bigint PRIMARY KEY,
    name varchar(255) NOT NULL
);
CREATE TABLE sport_sections (
    id bigint PRIMARY KEY,
    name varchar(255) NOT NULL
);
CREATE TABLE sport_teachers (
    isu bigint PRIMARY KEY,
    name varchar(255) NOT NULL
);
CREATE TABLE sport_time_slots (
    id bigint PRIMARY KEY,
    time_start varchar(255) NOT NULL,
    time_end varchar(255) NOT NULL
);

CREATE TABLE sport_lessons (
    id bigint PRIMARY KEY,
    section_id bigint NOT NULL,
    section_level bigint NOT NULL,
    lesson_level bigint NOT NULL,
    type_id bigint NOT NULL,
    section_name varchar(255) NOT NULL,
    time_slot_id bigint NOT NULL,
    building_id bigint NOT NULL,
    teacher_isu bigint NOT NULL,
    room_id bigint NOT NULL,
    room_name varchar(255) NOT NULL,
    starts_at timestamp(6) with time zone NOT NULL,
    ends_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT fk_sport_lessons_section FOREIGN KEY (section_id) REFERENCES sport_sections (id),
    CONSTRAINT fk_sport_lessons_time_slot FOREIGN KEY (time_slot_id) REFERENCES sport_time_slots (id),
    CONSTRAINT fk_sport_lessons_building FOREIGN KEY (building_id) REFERENCES sport_buildings (id),
    CONSTRAINT fk_sport_lessons_teacher FOREIGN KEY (teacher_isu) REFERENCES sport_teachers (isu)
);
CREATE INDEX idx_sport_lessons_start ON sport_lessons (starts_at);
CREATE INDEX idx_sport_lessons_end ON sport_lessons (ends_at);
CREATE INDEX idx_sport_lessons_section ON sport_lessons (section_id);
CREATE INDEX idx_sport_lessons_time_slot ON sport_lessons (time_slot_id);
CREATE INDEX idx_sport_lessons_building ON sport_lessons (building_id);
CREATE INDEX idx_sport_lessons_teacher ON sport_lessons (teacher_isu);

CREATE TABLE sport_auto_sign_entries (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id uuid NOT NULL,
    prototype_lesson_id bigint NOT NULL,
    real_lesson_id bigint,
    status varchar(255) NOT NULL DEFAULT 'WAITING',
    is_cancelled boolean NOT NULL DEFAULT false,
    created_at timestamp(6) with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    first_notified_at timestamp(6) with time zone,
    last_notified_at timestamp(6) with time zone,
    cancelled_at timestamp(6) with time zone,
    satisfied_at timestamp(6) with time zone,
    expired_at timestamp(6) with time zone,
    max_notification_attempts integer NOT NULL DEFAULT 10,
    notification_attempts integer NOT NULL DEFAULT 0,
    CONSTRAINT fk_auto_sign_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_auto_sign_prototype FOREIGN KEY (prototype_lesson_id) REFERENCES sport_lessons (id),
    CONSTRAINT fk_auto_sign_real FOREIGN KEY (real_lesson_id) REFERENCES sport_lessons (id),
    CONSTRAINT ck_auto_sign_status CHECK (status IN ('WAITING', 'NOTIFIED', 'GAVE_UP_NOTIFYING', 'SATISFIED', 'EXPIRED'))
);
CREATE INDEX idx_auto_sign_user_created ON sport_auto_sign_entries (user_id, created_at);
CREATE INDEX idx_auto_sign_prototype_status ON sport_auto_sign_entries (prototype_lesson_id, status, created_at);
CREATE INDEX idx_auto_sign_real ON sport_auto_sign_entries (real_lesson_id);

CREATE TABLE sport_free_sign_entries (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id uuid NOT NULL,
    lesson_id bigint NOT NULL,
    status varchar(255) NOT NULL DEFAULT 'WAITING',
    is_cancelled boolean NOT NULL DEFAULT false,
    created_at timestamp(6) with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    first_notified_at timestamp(6) with time zone,
    last_notified_at timestamp(6) with time zone,
    cancelled_at timestamp(6) with time zone,
    satisfied_at timestamp(6) with time zone,
    expired_at timestamp(6) with time zone,
    force_sign boolean NOT NULL,
    notification_attempts integer NOT NULL DEFAULT 0,
    max_notification_attempts integer NOT NULL DEFAULT 10,
    CONSTRAINT fk_free_sign_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_free_sign_lesson FOREIGN KEY (lesson_id) REFERENCES sport_lessons (id),
    CONSTRAINT ck_free_sign_status CHECK (status IN ('WAITING', 'NOTIFIED', 'GAVE_UP_NOTIFYING', 'SATISFIED', 'EXPIRED'))
);
CREATE INDEX idx_free_sign_user_created ON sport_free_sign_entries (user_id, created_at);
CREATE INDEX idx_free_sign_lesson_status ON sport_free_sign_entries (lesson_id, status, created_at);

CREATE TABLE user_sport_lessons (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id uuid NOT NULL,
    lesson_id bigint NOT NULL,
    created_at timestamp(6) with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_sport_lesson UNIQUE (user_id, lesson_id),
    CONSTRAINT fk_user_sport_lesson_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_sport_lesson_lesson FOREIGN KEY (lesson_id) REFERENCES sport_lessons (id)
);
CREATE INDEX idx_user_sport_lessons_lesson ON user_sport_lessons (lesson_id);

CREATE TABLE sport_update_logs (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    update_timestamp timestamp(6) with time zone NOT NULL,
    new_lessons_added integer NOT NULL
);
CREATE INDEX idx_sport_update_logs_timestamp ON sport_update_logs (update_timestamp);

CREATE TABLE sport_update_logs_new_lessons (
    sport_update_log_id bigint NOT NULL,
    new_lessons_id bigint NOT NULL,
    PRIMARY KEY (sport_update_log_id, new_lessons_id),
    CONSTRAINT uq_sport_update_log_lesson UNIQUE (new_lessons_id),
    CONSTRAINT fk_sport_update_log_link FOREIGN KEY (sport_update_log_id) REFERENCES sport_update_logs (id),
    CONSTRAINT fk_sport_update_log_new_lesson FOREIGN KEY (new_lessons_id) REFERENCES sport_lessons (id)
);
