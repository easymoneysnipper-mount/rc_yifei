CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    request_hash CHAR(64) NOT NULL,
    target_url VARCHAR(2048) NOT NULL,
    headers_json TEXT NOT NULL,
    body_json TEXT,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_http_status INTEGER,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT notifications_status_check CHECK (status IN ('PENDING', 'DELIVERING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT notifications_attempts_check CHECK (attempts >= 0)
);

CREATE INDEX notifications_due_idx
    ON notifications (next_attempt_at, id)
    WHERE status = 'PENDING';

CREATE INDEX notifications_expired_lease_idx
    ON notifications (lease_until, id)
    WHERE status = 'DELIVERING';
