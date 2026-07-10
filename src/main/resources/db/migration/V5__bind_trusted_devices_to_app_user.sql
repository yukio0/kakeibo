ALTER TABLE trusted_devices
    ADD COLUMN app_user_id BIGINT;

UPDATE trusted_devices
SET app_user_id = (
    SELECT id
    FROM app_user
    ORDER BY id
    LIMIT 1
)
WHERE app_user_id IS NULL;

DELETE FROM trusted_devices
WHERE app_user_id IS NULL;

ALTER TABLE trusted_devices
    ALTER COLUMN app_user_id SET NOT NULL;

ALTER TABLE trusted_devices
    ADD CONSTRAINT fk_trusted_devices_app_user
        FOREIGN KEY (app_user_id) REFERENCES app_user (id) ON DELETE CASCADE;

CREATE INDEX idx_trusted_devices_app_user_id
    ON trusted_devices (app_user_id);
