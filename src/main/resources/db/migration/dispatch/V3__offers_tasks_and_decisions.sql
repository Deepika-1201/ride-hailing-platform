-- Offers, search tasks, the decision log and driver stats (LLD §4.6, §8.3–§8.7).

CREATE TABLE dispatch.offers (
    id           uuid        PRIMARY KEY,
    ride_id      uuid        NOT NULL,
    driver_id    uuid        NOT NULL,
    attempt      int         NOT NULL,
    status       text        NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'WITHDRAWN')),
    rank         smallint    NOT NULL,
    distance_m   int         NOT NULL,
    eta_s        int,                                     -- V4 ETA ranking
    created_at   timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    seen_at      timestamptz,                             -- first fetch or WebSocket acknowledgement
    responded_at timestamptz,
    end_reason   text,                                    -- DRIVER, DRIVER_OFFLINE, RIDER_CANCELLED, SEARCH_TIMEOUT, …
    version      int         NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX one_pending_offer_per_driver ON dispatch.offers (driver_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX one_pending_offer_per_ride ON dispatch.offers (ride_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX one_offer_per_ride_and_driver ON dispatch.offers (ride_id, driver_id);    -- FR-DS3

CREATE TABLE dispatch.search_tasks (
    ride_id    uuid             PRIMARY KEY,
    city_id    text             NOT NULL,
    category   text             NOT NULL,
    pickup_lat double precision NOT NULL,
    pickup_lon double precision NOT NULL,
    priority   smallint         NOT NULL DEFAULT 0,       -- 1 for reassigned rides
    attempt    int              NOT NULL DEFAULT 0,
    radius_m   int              NOT NULL,
    due_at     timestamptz,                               -- NULL while an offer is pending
    backoff_s  int              NOT NULL DEFAULT 0,       -- live-index outage backoff
    created_at timestamptz      NOT NULL,
    updated_at timestamptz      NOT NULL
);

CREATE INDEX search_tasks_due ON dispatch.search_tasks (priority DESC, due_at) WHERE due_at IS NOT NULL;

CREATE TABLE dispatch.decisions (                         -- FR-DS6, kept 30 days
    id               uuid        PRIMARY KEY,
    ride_id          uuid        NOT NULL,
    attempt          int         NOT NULL,
    created_at       timestamptz NOT NULL,
    strategy         text        NOT NULL,
    strategy_version text        NOT NULL,
    radius_m         int         NOT NULL,
    outcome          text        NOT NULL CHECK (outcome IN ('OFFERED', 'NO_CANDIDATES', 'ALL_RESERVATIONS_LOST',
                                                             'INDEX_UNAVAILABLE')),
    chosen_driver_id uuid,
    offer_id         uuid,
    detail           jsonb       NOT NULL,                -- candidates, exclusions, reservation tries (§8.3)
    duration_us      int         NOT NULL
);

CREATE INDEX decisions_by_ride ON dispatch.decisions (ride_id, created_at);
CREATE INDEX decisions_retention ON dispatch.decisions (created_at);

CREATE TABLE dispatch.driver_stats (                      -- acceptance and cancellation rates (V4 ranking)
    driver_id              uuid        PRIMARY KEY,
    offers                 int         NOT NULL DEFAULT 0,
    accepted               int         NOT NULL DEFAULT 0,
    declined               int         NOT NULL DEFAULT 0,
    expired                int         NOT NULL DEFAULT 0,
    cancelled_after_accept int         NOT NULL DEFAULT 0,
    updated_at             timestamptz NOT NULL
);
