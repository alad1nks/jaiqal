CREATE TABLE deleted_firebase_identities (
    firebase_uid_hash CHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL,
    deleted_at TIMESTAMPTZ NOT NULL
);
