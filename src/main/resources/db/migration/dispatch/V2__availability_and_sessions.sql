-- Driver availability and online sessions (LLD §4.6, §8.1, §8.2). Offers, search tasks and decisions arrive in phase 7.

CREATE TABLE dispatch.driver_availability (            -- one row per driver, kept while offline
    driver_id           uuid        PRIMARY KEY,
    city_id             text        NOT NULL,
    status              text        NOT NULL CHECK (status IN ('OFFLINE', 'AVAILABLE', 'OFFERED', 'ASSIGNED', 'ON_TRIP')),
    category            text,
    vehicle_id          uuid,
    offer_id            uuid,
    ride_id             uuid,
    consecutive_expired smallint    NOT NULL DEFAULT 0,
    offline_after_ride  boolean     NOT NULL DEFAULT false,  -- suspended during a ride
    online_since        timestamptz,
    status_changed_at   timestamptz NOT NULL,
    version             bigint      NOT NULL DEFAULT 0,      -- monotonic for the driver's lifetime (§9.4)
    CHECK ((status = 'OFFERED') = (offer_id IS NOT NULL)),
    CHECK ((status IN ('ASSIGNED', 'ON_TRIP')) = (ride_id IS NOT NULL)),
    CHECK ((status = 'OFFLINE') = (online_since IS NULL)),
    CHECK (status = 'OFFLINE' OR (category IS NOT NULL AND vehicle_id IS NOT NULL))
);

CREATE INDEX availability_online ON dispatch.driver_availability (city_id, status) WHERE status <> 'OFFLINE';

CREATE TABLE dispatch.driver_sessions (
    id             uuid        PRIMARY KEY,
    driver_id      uuid        NOT NULL,
    city_id        text        NOT NULL,
    vehicle_id     uuid        NOT NULL,
    category       text        NOT NULL,
    online_at      timestamptz NOT NULL,
    offline_at     timestamptz,
    offline_reason text        CHECK (offline_reason IN ('DRIVER', 'SILENT', 'UNREACHABLE', 'UNRESPONSIVE', 'SUSPENDED')),
    CHECK ((offline_at IS NULL) = (offline_reason IS NULL))
);

CREATE INDEX sessions_by_driver ON dispatch.driver_sessions (driver_id, online_at DESC);
-- A driver is online at most once at a time.
CREATE UNIQUE INDEX sessions_one_open ON dispatch.driver_sessions (driver_id) WHERE offline_at IS NULL;
