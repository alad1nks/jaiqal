CREATE TABLE alert_rule_state (
    rule_id UUID PRIMARY KEY REFERENCES alert_rules(id) ON DELETE CASCADE,
    condition_since TIMESTAMPTZ,
    recovery_since TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE notification_outbox
    ADD COLUMN notification_key VARCHAR(255),
    ADD CONSTRAINT notification_outbox_notification_key_unique UNIQUE (notification_key);

CREATE INDEX devices_last_seen_at_idx ON devices(last_seen_at) WHERE disabled_at IS NULL;
