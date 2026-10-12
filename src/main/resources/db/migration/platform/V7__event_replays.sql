CREATE TABLE platform.event_replays (
    id uuid PRIMARY KEY,
    consumer text NOT NULL,
    from_at timestamptz NOT NULL,
    to_at timestamptz NOT NULL CHECK (to_at > from_at),
    reprocess boolean NOT NULL,
    generation bigint NOT NULL DEFAULT 0 CHECK (generation >= 0),
    status text NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'DONE', 'FAILED')),
    events bigint NOT NULL DEFAULT 0 CHECK (events >= 0),
    total_events bigint NOT NULL DEFAULT 0 CHECK (total_events >= events),
    error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status = 'FAILED') = (error IS NOT NULL))
);

CREATE INDEX event_replays_pending ON platform.event_replays (created_at, id) WHERE status = 'RUNNING';
CREATE INDEX event_replays_finished ON platform.event_replays (updated_at, id) WHERE status <> 'RUNNING';

CREATE TABLE platform.event_replay_items (
    replay_id uuid NOT NULL REFERENCES platform.event_replays(id) ON DELETE CASCADE,
    outbox_id bigint NOT NULL,
    envelope jsonb NOT NULL CHECK (jsonb_typeof(envelope) = 'object'),
    PRIMARY KEY (replay_id, outbox_id)
);