CREATE TABLE device_claim_codes (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    code_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);
CREATE INDEX device_claim_codes_device_id_idx ON device_claim_codes(device_id);
CREATE INDEX device_claim_codes_active_idx ON device_claim_codes(code_hash, expires_at) WHERE consumed_at IS NULL;
