CREATE TABLE device_ingestion_quotas (
    device_id UUID PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    window_started_at TIMESTAMPTZ NOT NULL,
    measurements_used INTEGER NOT NULL CHECK (measurements_used >= 0),
    updated_at TIMESTAMPTZ NOT NULL
);
