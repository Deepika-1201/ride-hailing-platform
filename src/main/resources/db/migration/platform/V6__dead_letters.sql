CREATE TABLE platform.dead_letters (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    consumer text NOT NULL,
    topic text NOT NULL,
    kafka_partition integer NOT NULL CHECK (kafka_partition >= 0),
    kafka_offset bigint NOT NULL CHECK (kafka_offset >= 0),
    event_id uuid,
    error text NOT NULL,
    record_key bytea,
    record_value bytea,
    record_headers bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    UNIQUE (topic, kafka_partition, kafka_offset)
);

CREATE INDEX dead_letters_open ON platform.dead_letters (created_at, id) WHERE resolved_at IS NULL;
CREATE INDEX dead_letters_resolved ON platform.dead_letters (resolved_at, id) WHERE resolved_at IS NOT NULL;