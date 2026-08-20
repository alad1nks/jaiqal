ALTER TABLE devices
    ADD COLUMN anomaly_window_started_at TIMESTAMPTZ,
    ADD COLUMN quota_breached_windows INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_breached_quota_window_at TIMESTAMPTZ,
    ADD COLUMN quarantined_at TIMESTAMPTZ,
    ADD COLUMN quarantine_until TIMESTAMPTZ,
    ADD CONSTRAINT devices_quota_breached_windows_check CHECK (quota_breached_windows >= 0),
    ADD CONSTRAINT devices_quarantine_times_check CHECK (
        (quarantined_at IS NULL AND quarantine_until IS NULL) OR
        (quarantined_at IS NOT NULL AND quarantine_until > quarantined_at)
    );

CREATE INDEX devices_active_quarantine_idx
    ON devices(quarantine_until)
    WHERE quarantine_until IS NOT NULL;
