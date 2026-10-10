-- Device registration v2 (BK-16b): the platform a device registered from, whether an iOS device allows alerts,
-- the push provider of its token and when the row was created. Expand-only: fcm_token keeps its name, app_version
-- comes from V11. Existing rows are Android devices registered through FCM, which the defaults state. Backend
-- 1.7.0 and 1.8.0 ignore the columns and their inserts get the defaults, so a rollback stays image-only.

-- Non-volatile defaults (now() is evaluated once) change only the catalog: no table rewrite.
ALTER TABLE devices
    ADD COLUMN platform varchar(16) NOT NULL DEFAULT 'ANDROID',
    ADD COLUMN alerts_allowed boolean NOT NULL DEFAULT true,
    ADD COLUMN push_provider varchar(16) NOT NULL DEFAULT 'FCM',
    ADD COLUMN created_at timestamp(6) with time zone NOT NULL DEFAULT now(),
    ADD CONSTRAINT ck_devices_platform CHECK (platform IN ('ANDROID', 'IOS'));
