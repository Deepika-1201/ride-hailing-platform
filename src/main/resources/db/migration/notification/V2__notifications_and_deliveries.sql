-- Notifications and their deliveries (LLD §4.7, §15.4): one notification per event, recipient and kind.

CREATE TABLE notification.notifications (
    id           uuid        PRIMARY KEY,
    recipient_id uuid        NOT NULL,
    kind         text        NOT NULL,
    ride_id      uuid,
    event_id     uuid,
    payload      jsonb       NOT NULL,
    created_at   timestamptz NOT NULL,
    UNIQUE (event_id, recipient_id, kind)
);

CREATE TABLE notification.deliveries (
    id              uuid        PRIMARY KEY,
    notification_id uuid        NOT NULL REFERENCES notification.notifications (id),
    channel         text        NOT NULL CHECK (channel IN ('PUSH', 'SMS')),
    status          text        NOT NULL CHECK (status IN ('PENDING', 'SENT', 'DEAD')),
    attempts        smallint    NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,                 -- while PENDING; also the lease of a send in progress
    last_error      text,
    sent_at         timestamptz,
    CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

CREATE INDEX deliveries_due ON notification.deliveries (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX deliveries_by_notification ON notification.deliveries (notification_id);
