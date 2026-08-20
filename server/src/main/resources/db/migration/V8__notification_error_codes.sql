UPDATE notification_outbox
SET last_error = 'DELIVERY_FAILED'
WHERE last_error IS NOT NULL;

ALTER TABLE notification_outbox
    ADD CONSTRAINT notification_outbox_last_error_code_check
    CHECK (
        last_error IS NULL OR last_error IN (
            'AUTHENTICATION_FAILED',
            'RATE_LIMITED',
            'PROVIDER_UNAVAILABLE',
            'INVALID_DESTINATION',
            'DELIVERY_FAILED'
        )
    );
