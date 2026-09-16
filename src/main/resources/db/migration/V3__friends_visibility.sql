ALTER TABLE user_settings
    ADD COLUMN friends_visibility VARCHAR(16) NOT NULL DEFAULT 'ALL',
    ADD CONSTRAINT ck_settings_friends_visibility
        CHECK (friends_visibility IN ('ALL', 'FRIENDS', 'NOBODY'));
