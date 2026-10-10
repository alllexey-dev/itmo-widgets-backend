-- The app build each device last reported in the X-App-Version request header (BK-VER1): version name, version
-- code, platform, distribution and when it was last seen. Expand-only: nullable columns, no backfill. Devices of
-- Android 2.2 and older keep nulls, because those apps send no header. Backend 1.7.0 ignores the columns, so a
-- rollback stays image-only.

-- Nullable columns without defaults change only the catalog: no table rewrite. IF NOT EXISTS is harmless here;
-- the later device_platform migration must not add app_version again.
ALTER TABLE devices ADD COLUMN IF NOT EXISTS app_version varchar(32);

ALTER TABLE devices
    ADD COLUMN app_build integer,
    ADD COLUMN app_platform varchar(16),
    ADD COLUMN app_distribution varchar(16),
    ADD COLUMN app_version_seen_at timestamp(6) with time zone,
    ADD CONSTRAINT ck_devices_app_build CHECK (app_build > 0),
    ADD CONSTRAINT ck_devices_app_platform CHECK (app_platform IN ('ANDROID', 'IOS')),
    -- A reported build is stored whole; app_version alone may also come from device registration.
    ADD CONSTRAINT ck_devices_app_report CHECK (
        (app_build IS NULL) = (app_version_seen_at IS NULL)
        AND (app_platform IS NULL) = (app_version_seen_at IS NULL)
        AND (app_distribution IS NULL) = (app_version_seen_at IS NULL)
        AND (app_version_seen_at IS NULL OR app_version IS NOT NULL)
    );
