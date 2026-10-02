-- The append-only audit log, partitioned by month (LLD §4.2, §5.6). This migration creates the partitions for the
-- current month and the next two, so writes work before the maintenance job first runs (LLD §4.9).

CREATE TABLE audit.audit_log (
    id             uuid        NOT NULL,
    occurred_at    timestamptz NOT NULL,
    actor_type     text        NOT NULL CHECK (actor_type IN ('RIDER', 'DRIVER', 'OPS', 'ADMIN', 'SYSTEM')),
    actor_id       text,
    action         text        NOT NULL,
    entity_type    text        NOT NULL,
    entity_id      text        NOT NULL,
    reason         text,
    request_id     text,
    correlation_id text,
    before_state   jsonb,
    after_state    jsonb,
    PRIMARY KEY (occurred_at, id)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX audit_by_entity ON audit.audit_log (entity_type, entity_id, occurred_at);

-- Rejects row changes; dropping a whole expired partition is still possible.
CREATE FUNCTION audit.reject_change() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not allowed', TG_OP;
END
$$;

CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE ON audit.audit_log
    FOR EACH ROW EXECUTE FUNCTION audit.reject_change();

-- Month arithmetic on timestamp without time zone, and bounds with an explicit UTC offset, so the session time zone
-- doesn't matter.
DO
$$
DECLARE
    first_month timestamp := date_trunc('month', now() AT TIME ZONE 'UTC');
    month_start timestamp;
BEGIN
    FOR offset_months IN 0..2 LOOP
        month_start := first_month + make_interval(months => offset_months);
        EXECUTE format('CREATE TABLE audit.%I PARTITION OF audit.audit_log FOR VALUES FROM (%L) TO (%L)',
                       'audit_log_' || to_char(month_start, 'YYYY_MM'),
                       to_char(month_start, 'YYYY-MM-DD') || ' 00:00:00+00',
                       to_char(month_start + interval '1 month', 'YYYY-MM-DD') || ' 00:00:00+00');
    END LOOP;
END
$$;
