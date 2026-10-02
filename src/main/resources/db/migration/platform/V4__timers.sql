-- Durable timers (ADR-005, LLD §5.4). Rows churn by design, so autovacuum runs after every 1,000 dead rows.

CREATE TABLE platform.timers (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    kind         text        NOT NULL,
    aggregate_id uuid        NOT NULL,
    payload      jsonb       NOT NULL DEFAULT '{}',
    due_at       timestamptz NOT NULL,
    attempts     int         NOT NULL DEFAULT 0,
    last_error   text,
    parked_at    timestamptz,
    trace_parent text,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX timers_due ON platform.timers (due_at) WHERE parked_at IS NULL;
CREATE INDEX timers_by_aggregate ON platform.timers (aggregate_id, kind);

ALTER TABLE platform.timers SET (
    autovacuum_vacuum_scale_factor = 0,
    autovacuum_vacuum_threshold = 1000,
    autovacuum_vacuum_cost_delay = 0);
