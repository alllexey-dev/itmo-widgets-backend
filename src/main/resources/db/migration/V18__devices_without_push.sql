-- Devices without a push token: an iOS build without push (an unsigned or free Apple ID build) never
-- registers, so Backend creates its device from its X-App-Version report. Such a row has no fcm_token and the push
-- provider NONE; a later registration with a token takes the row over. At most one such row per user and platform,
-- so concurrent reports cannot create two.
--
-- Expand-only. Backend creates these rows only for iOS and with alerts_allowed false, which every image since
-- device_platform already leaves out of deliveries, so a rollback stays image-only. No backfill: no existing row is
-- an iOS device, since no iOS build ever registered.

-- Dropping NOT NULL and adding a CHECK change only the catalog; the CHECK scans the table once, which is small.
ALTER TABLE devices
    ALTER COLUMN fcm_token DROP NOT NULL,
    ADD CONSTRAINT ck_devices_push_provider CHECK (
        push_provider IN ('FCM', 'NONE') AND (fcm_token IS NULL) = (push_provider = 'NONE')
    );

CREATE UNIQUE INDEX uq_devices_user_platform_without_push ON devices (user_id, platform) WHERE fcm_token IS NULL;
