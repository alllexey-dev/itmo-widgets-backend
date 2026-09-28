-- One row per secret of an external service; values are stored as plain text, as in my_itmo_storage.
-- my_itmo_storage stays untouched so that an image-only rollback keeps working: the previous image reads it
-- after the rollback. A separate migration of the next release drops it.
CREATE TABLE service_credentials (
    key varchar(64) PRIMARY KEY,
    value text,
    expires_at timestamp(6) with time zone,
    status varchar(16) NOT NULL DEFAULT 'MISSING',
    last_used_at timestamp(6) with time zone,
    last_renewed_at timestamp(6) with time zone,
    last_error_at timestamp(6) with time zone,
    last_error varchar(300),
    updated_at timestamp(6) with time zone NOT NULL,
    updated_by uuid,
    updated_source varchar(16),
    CONSTRAINT ck_service_credentials_key CHECK (
        key IN ('MY_ITMO_REFRESH_TOKEN', 'MY_ITMO_ACCESS_TOKEN', 'MY_ITMO_ID_TOKEN', 'ISU_KEYCLOAK_IDENTITY')
    ),
    CONSTRAINT ck_service_credentials_status CHECK (status IN ('MISSING', 'UNKNOWN', 'OK', 'EXPIRED', 'FAILED')),
    CONSTRAINT ck_service_credentials_present CHECK ((value IS NULL) = (status = 'MISSING')),
    CONSTRAINT ck_service_credentials_source CHECK (updated_source IN ('MIGRATION', 'SEED', 'ROTATION', 'ADMIN')),
    CONSTRAINT ck_service_credentials_admin CHECK (updated_by IS NULL OR updated_source = 'ADMIN'),
    CONSTRAINT fk_service_credentials_admin FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE SET NULL
);

-- Copies the MyITMO credential without changing my_itmo_storage; blank tokens become missing rows.
INSERT INTO service_credentials (key, value, expires_at, status, updated_at, updated_source)
SELECT 'MY_ITMO_REFRESH_TOKEN', copied.value,
    CASE WHEN copied.value IS NOT NULL AND copied.expires_at > 0 THEN to_timestamp(copied.expires_at / 1000.0) END,
    CASE WHEN copied.value IS NULL THEN 'MISSING' ELSE 'UNKNOWN' END,
    now(),
    CASE WHEN copied.value IS NOT NULL THEN 'MIGRATION' END
FROM (
    SELECT NULLIF(btrim(refresh_token), '') AS value, refresh_token_expires_at AS expires_at
    FROM my_itmo_storage WHERE id = 1
) copied;

INSERT INTO service_credentials (key, value, expires_at, status, updated_at, updated_source)
SELECT 'MY_ITMO_ACCESS_TOKEN', copied.value,
    CASE WHEN copied.value IS NOT NULL AND copied.expires_at > 0 THEN to_timestamp(copied.expires_at / 1000.0) END,
    CASE WHEN copied.value IS NULL THEN 'MISSING' ELSE 'UNKNOWN' END,
    now(),
    CASE WHEN copied.value IS NOT NULL THEN 'MIGRATION' END
FROM (
    SELECT NULLIF(btrim(access_token), '') AS value, access_token_expires_at AS expires_at
    FROM my_itmo_storage WHERE id = 1
) copied;

INSERT INTO service_credentials (key, value, expires_at, status, updated_at, updated_source)
SELECT 'MY_ITMO_ID_TOKEN', copied.value,
    NULL,
    CASE WHEN copied.value IS NULL THEN 'MISSING' ELSE 'UNKNOWN' END,
    now(),
    CASE WHEN copied.value IS NOT NULL THEN 'MIGRATION' END
FROM (
    SELECT NULLIF(btrim(id_token), '') AS value
    FROM my_itmo_storage WHERE id = 1
) copied;

-- All four rows always exist, even without a my_itmo_storage row.
INSERT INTO service_credentials (key, updated_at) VALUES
    ('MY_ITMO_REFRESH_TOKEN', now()),
    ('MY_ITMO_ACCESS_TOKEN', now()),
    ('MY_ITMO_ID_TOKEN', now()),
    ('ISU_KEYCLOAK_IDENTITY', now())
ON CONFLICT (key) DO NOTHING;
