CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE plants (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    name VARCHAR(255) NOT NULL,
    species VARCHAR(255),
    image_url VARCHAR(2048),
    created_at TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ
);
CREATE INDEX plants_user_id_idx ON plants(user_id);

CREATE TABLE devices (
    id UUID PRIMARY KEY,
    plant_id UUID REFERENCES plants(id) ON DELETE RESTRICT,
    name VARCHAR(255) NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    firmware_version VARCHAR(100),
    last_seen_at TIMESTAMPTZ,
    soil_dry_raw INTEGER,
    soil_wet_raw INTEGER,
    disabled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX devices_plant_id_idx ON devices(plant_id);

CREATE TABLE measurements (
    id BIGSERIAL PRIMARY KEY,
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE RESTRICT,
    sequence BIGINT NOT NULL,
    measured_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    soil_moisture_raw INTEGER,
    soil_moisture_percent DOUBLE PRECISION,
    air_temperature_celsius DOUBLE PRECISION,
    air_humidity_percent DOUBLE PRECISION,
    light_raw INTEGER,
    extra JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT measurements_device_sequence_key UNIQUE(device_id, sequence)
);
CREATE INDEX measurements_device_measured_at_idx ON measurements(device_id, measured_at DESC);

CREATE TABLE device_latest_state (
    device_id UUID PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    measurement_id BIGINT NOT NULL REFERENCES measurements(id) ON DELETE RESTRICT,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by_id UUID REFERENCES refresh_tokens(id) ON DELETE SET NULL
);
CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens(user_id);
CREATE INDEX refresh_tokens_expires_at_idx ON refresh_tokens(expires_at);

CREATE TABLE alert_rules (
    id UUID PRIMARY KEY,
    plant_id UUID NOT NULL REFERENCES plants(id) ON DELETE CASCADE,
    type VARCHAR(50) NOT NULL,
    threshold DOUBLE PRECISION,
    required_duration_seconds BIGINT NOT NULL DEFAULT 0 CHECK (required_duration_seconds >= 0),
    recovery_duration_seconds BIGINT NOT NULL DEFAULT 0 CHECK (recovery_duration_seconds >= 0),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(plant_id, type)
);
CREATE INDEX alert_rules_plant_id_idx ON alert_rules(plant_id);

CREATE TABLE alert_events (
    id UUID PRIMARY KEY,
    plant_id UUID NOT NULL REFERENCES plants(id) ON DELETE CASCADE,
    rule_id UUID REFERENCES alert_rules(id) ON DELETE SET NULL,
    type VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    triggered_at TIMESTAMPTZ NOT NULL,
    recovered_at TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ,
    last_observed_at TIMESTAMPTZ NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX alert_events_plant_triggered_idx ON alert_events(plant_id, triggered_at DESC);
CREATE UNIQUE INDEX alert_events_one_active_idx ON alert_events(plant_id, type) WHERE status = 'ACTIVE';

CREATE TABLE notification_outbox (
    id BIGSERIAL PRIMARY KEY,
    alert_event_id UUID NOT NULL REFERENCES alert_events(id) ON DELETE CASCADE,
    channel VARCHAR(30) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ,
    locked_by VARCHAR(255),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);
CREATE INDEX notification_outbox_pending_idx ON notification_outbox(status, available_at);
