LOCK TABLE measurements, device_latest_state IN ACCESS EXCLUSIVE MODE;

ALTER TABLE device_latest_state
    DROP CONSTRAINT device_latest_state_measurement_id_fkey;

ALTER SEQUENCE measurements_id_seq OWNED BY NONE;

CREATE TABLE measurements_partitioned (
    id BIGINT NOT NULL DEFAULT nextval('measurements_id_seq'::regclass),
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
    PRIMARY KEY (device_id, id),
    UNIQUE (device_id, sequence)
) PARTITION BY HASH (device_id);

CREATE TABLE measurements_p00 PARTITION OF measurements_partitioned FOR VALUES WITH (MODULUS 16, REMAINDER 0);
CREATE TABLE measurements_p01 PARTITION OF measurements_partitioned FOR VALUES WITH (MODULUS 16, REMAINDER 1);
CREATE TABLE measurements_p02 PARTITION OF measurements_partitioned FOR VALUES WITH (MODULUS 16, REMAINDER 2);
CREATE TABLE measurements_p03 PARTITION OF measurements_partitioned FOR VALUES WITH (MODULUS 16, REMAINDER 3);
CREATE TABLE measurements_p04 PARTITION OF measurements_partitioned FOR VALUES WITH (MODULUS 16, REMAINDER 4);
CREATE TABLE measurements_p05 PARTITION OF measurements_partitioned FOR VALUES WITH (MODULUS 16, REMAINDER 5);
CREATE TABLE measurements_p06 PARTITION OF measurements_partitioned FOR VALUES WITH (MODULUS 16, REMAINDER 6);
CREATE TABLE measurements_p07 PARTITION OF measurements_partitioned FOR VALUES WITH (MODULUS 16, REMAINDER 7);
CREATE TABLE measurements_p08 PARTITION OF measurements_partitioned FOR VALUES WITH (MODULUS 16, REMAINDER 8);
CREATE TABLE measurements_p09 PARTITION OF measurements_partitioned FOR VALUES WITH (MODULUS 16, REMAINDER 9);
CREATE TABLE measurements_p10 PARTITION OF measurements_partitioned FOR VALUES WITH (MODULUS 16, REMAINDER 10);
CREATE TABLE measurements_p11 PARTITION OF measurements_partitioned FOR VALUES WITH (MODULUS 16, REMAINDER 11);
CREATE TABLE measurements_p12 PARTITION OF measurements_partitioned FOR VALUES WITH (MODULUS 16, REMAINDER 12);
CREATE TABLE measurements_p13 PARTITION OF measurements_partitioned FOR VALUES WITH (MODULUS 16, REMAINDER 13);
CREATE TABLE measurements_p14 PARTITION OF measurements_partitioned FOR VALUES WITH (MODULUS 16, REMAINDER 14);
CREATE TABLE measurements_p15 PARTITION OF measurements_partitioned FOR VALUES WITH (MODULUS 16, REMAINDER 15);

INSERT INTO measurements_partitioned (
    id,
    device_id,
    sequence,
    measured_at,
    received_at,
    soil_moisture_raw,
    soil_moisture_percent,
    air_temperature_celsius,
    air_humidity_percent,
    light_raw,
    extra
)
SELECT
    id,
    device_id,
    sequence,
    measured_at,
    received_at,
    soil_moisture_raw,
    soil_moisture_percent,
    air_temperature_celsius,
    air_humidity_percent,
    light_raw,
    extra
FROM measurements;

ALTER TABLE measurements RENAME TO measurements_unpartitioned;
ALTER TABLE measurements_partitioned RENAME TO measurements;
DROP TABLE measurements_unpartitioned;

ALTER SEQUENCE measurements_id_seq OWNED BY measurements.id;

CREATE INDEX measurements_device_measured_at_idx
    ON measurements(device_id, measured_at DESC);
CREATE INDEX measurements_received_at_idx
    ON measurements(received_at, device_id, id);

ALTER TABLE device_latest_state
    ADD CONSTRAINT device_latest_state_measurement_fkey
        FOREIGN KEY (device_id, measurement_id)
        REFERENCES measurements(device_id, id)
        ON DELETE RESTRICT;
