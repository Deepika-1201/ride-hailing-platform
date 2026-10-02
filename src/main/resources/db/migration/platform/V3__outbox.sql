-- The transactional outbox, the consumers' inbox and their set-aside deliveries (ADR-008, LLD §5.2, §5.3).

CREATE TABLE platform.outbox (
    id                bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id          uuid        NOT NULL UNIQUE,
    event_type        text        NOT NULL,
    event_version     int         NOT NULL,
    aggregate_type    text        NOT NULL,
    aggregate_id      uuid        NOT NULL,
    aggregate_version bigint      NOT NULL,
    partition_key     uuid        NOT NULL,
    occurred_at       timestamptz NOT NULL,
    producer          text        NOT NULL,
    correlation_id    text        NOT NULL,
    causation_id      text,
    trace_parent      text,
    payload           jsonb       NOT NULL,
    published_at      timestamptz
);

CREATE INDEX outbox_unpublished ON platform.outbox (id) WHERE published_at IS NULL;
CREATE INDEX outbox_by_key ON platform.outbox (partition_key, id);
CREATE INDEX outbox_published ON platform.outbox (published_at) WHERE published_at IS NOT NULL;

CREATE TABLE platform.inbox (
    consumer     text        NOT NULL,
    event_id     uuid        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

CREATE INDEX inbox_processed ON platform.inbox (processed_at);

CREATE TABLE platform.failed_deliveries (
    consumer    text        NOT NULL,
    event_id    uuid        NOT NULL,
    outbox_id   bigint      NOT NULL,
    attempts    int         NOT NULL,
    last_error  text        NOT NULL,
    failed_at   timestamptz NOT NULL,
    redriven_at timestamptz,
    PRIMARY KEY (consumer, event_id)
);
