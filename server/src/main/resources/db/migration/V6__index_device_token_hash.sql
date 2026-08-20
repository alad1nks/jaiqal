DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM devices
        WHERE token_hash !~ '^[0-9A-Fa-f]{64}$'
    ) THEN
        RAISE EXCEPTION 'devices.token_hash contains values that are not 64-character SHA-256 hex';
    END IF;

    IF EXISTS (
        SELECT lower(token_hash)
        FROM devices
        GROUP BY lower(token_hash)
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'devices.token_hash contains duplicate SHA-256 values';
    END IF;
END $$;

UPDATE devices SET token_hash = lower(token_hash) WHERE token_hash <> lower(token_hash);

ALTER TABLE devices
    ALTER COLUMN token_hash TYPE VARCHAR(64),
    ADD CONSTRAINT devices_token_hash_sha256_hex_check
        CHECK (token_hash ~ '^[0-9a-f]{64}$');

CREATE UNIQUE INDEX devices_token_hash_unique_idx ON devices(token_hash);
