-- Rides and their transition log (LLD §4.5). Flags arrive with the commands that raise them in phase 8.

CREATE TABLE ride.rides (
    id                    uuid             PRIMARY KEY,
    rider_id              uuid             NOT NULL,
    city_id               text             NOT NULL,
    category              text             NOT NULL,
    quote_id              uuid             NOT NULL UNIQUE,
    pickup_lat            double precision NOT NULL,
    pickup_lon            double precision NOT NULL,
    dropoff_lat           double precision NOT NULL,
    dropoff_lon           double precision NOT NULL,
    pickup_zone           text             NOT NULL,
    distance_m            int              NOT NULL,
    duration_s            int              NOT NULL,
    fare_paise            bigint           NOT NULL,
    commission_paise      bigint           NOT NULL,
    currency              char(3)          NOT NULL,
    fee_rule_id           uuid             NOT NULL,
    payment_method_id     uuid             NOT NULL,
    payment_method_type   text             NOT NULL CHECK (payment_method_type IN ('CASH', 'CARD', 'UPI')),
    status                text             NOT NULL CHECK (status IN ('SEARCHING', 'DRIVER_ASSIGNED', 'DRIVER_ARRIVED',
                                               'IN_TRIP', 'COMPLETED', 'CANCELLED_BY_RIDER', 'CANCELLED_BY_DRIVER',
                                               'CANCELLED_BY_SYSTEM', 'DRIVER_NOT_FOUND')),
    version               int              NOT NULL DEFAULT 0,
    search_generation     int              NOT NULL DEFAULT 1,      -- +1 each time the ride re-enters SEARCHING
    driver_id             uuid,
    vehicle_id            uuid,
    offer_id              uuid,
    rider_snapshot        jsonb            NOT NULL,                -- first name at booking (NFR-10: no phone)
    driver_snapshot       jsonb,                                    -- first name and vehicle at assignment
    pin                   char(4),                                  -- shown only to the rider; never logged or published
    pin_attempts          smallint         NOT NULL DEFAULT 0,
    promised_pickup_eta_s int,
    requested_at          timestamptz      NOT NULL,
    assigned_at           timestamptz,
    arrived_at            timestamptz,
    started_at            timestamptz,
    completed_at          timestamptz,
    ended_at              timestamptz,
    start_device_time     timestamptz,
    complete_device_time  timestamptz,
    cancelled_by          text             CHECK (cancelled_by IN ('RIDER', 'DRIVER', 'SYSTEM')),
    cancel_reason         text,
    fee_purpose           text             CHECK (fee_purpose IN ('CANCELLATION_FEE', 'NO_SHOW_FEE')),
    fee_paise             bigint,
    reassign_count        int              NOT NULL DEFAULT 0,
    CHECK (status NOT IN ('DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_TRIP', 'COMPLETED') OR driver_id IS NOT NULL),
    CHECK ((fee_purpose IS NULL) = (fee_paise IS NULL))
);

CREATE UNIQUE INDEX one_active_ride_per_rider ON ride.rides (rider_id)
    WHERE status IN ('SEARCHING', 'DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_TRIP');
CREATE UNIQUE INDEX one_active_ride_per_driver ON ride.rides (driver_id)
    WHERE status IN ('DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_TRIP');
CREATE INDEX rides_rider_history ON ride.rides (rider_id, requested_at DESC, id);
CREATE INDEX rides_driver_history ON ride.rides (driver_id, requested_at DESC, id) WHERE driver_id IS NOT NULL;
CREATE INDEX rides_active ON ride.rides (city_id, status, requested_at)
    WHERE status IN ('SEARCHING', 'DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_TRIP');
CREATE INDEX rides_by_zone ON ride.rides (city_id, pickup_zone, requested_at);

CREATE TABLE ride.transitions (
    id          uuid        PRIMARY KEY,
    ride_id     uuid        NOT NULL REFERENCES ride.rides (id),
    version     int         NOT NULL,                               -- the ride's version after the transition
    from_status text,
    to_status   text        NOT NULL,
    command     text        NOT NULL,                               -- BOOK, ACCEPT, SEARCH_TIMEOUT, CANCEL, …
    actor_type  text        NOT NULL,
    actor_id    text,
    reason      text,
    occurred_at timestamptz NOT NULL,
    device_time timestamptz,
    request_id  text,
    UNIQUE (ride_id, version)
);
