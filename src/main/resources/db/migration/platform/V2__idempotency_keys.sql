-- API idempotency keys (ADR-009, LLD §5.1). response_status 0 marks a call whose transaction is still running.

CREATE TABLE platform.idempotency_keys (
    principal             text        NOT NULL,
    key                   text        NOT NULL CHECK (length(key) BETWEEN 1 AND 255),
    request_hash          bytea       NOT NULL,
    response_status       int         NOT NULL,
    response_content_type text,
    response_location     text,
    response_body         text,
    created_at            timestamptz NOT NULL DEFAULT now(),
    expires_at            timestamptz NOT NULL,
    PRIMARY KEY (principal, key)
);

CREATE INDEX idempotency_expiry ON platform.idempotency_keys (expires_at);
