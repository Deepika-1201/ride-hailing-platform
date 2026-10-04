-- The operations review queue (LLD §4.5, §7.11): at most one open flag per ride and kind.

CREATE TABLE ride.flags (
    id          uuid        PRIMARY KEY,
    ride_id     uuid        NOT NULL REFERENCES ride.rides (id),
    kind        text        NOT NULL CHECK (kind IN ('ARRIVED_FAR', 'DRIVER_CANCELLED_AT_PICKUP', 'PIN_LOCKED',
                                                     'OFFLINE_CONFLICT', 'STUCK')),
    details     jsonb       NOT NULL DEFAULT '{}',
    created_at  timestamptz NOT NULL,
    resolved_at timestamptz,
    resolved_by uuid,
    resolution  text,
    CHECK ((resolved_at IS NULL) = (resolution IS NULL))
);

CREATE UNIQUE INDEX one_open_flag_per_kind ON ride.flags (ride_id, kind) WHERE resolved_at IS NULL;
CREATE INDEX flags_open ON ride.flags (created_at) WHERE resolved_at IS NULL;
