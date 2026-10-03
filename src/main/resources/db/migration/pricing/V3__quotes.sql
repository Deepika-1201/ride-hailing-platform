-- Quotes: upfront fares, valid for a few minutes and used by at most one ride (LLD §4.4, §10.4). The fare is stored
-- as typed columns so that its arithmetic can be checked here too.

CREATE TABLE pricing.quotes (
    id                  uuid             PRIMARY KEY,
    rider_id            uuid             NOT NULL,
    city_id             text             NOT NULL,
    category            text             NOT NULL,
    pickup_lat          double precision NOT NULL CHECK (pickup_lat BETWEEN -90 AND 90),
    pickup_lon          double precision NOT NULL CHECK (pickup_lon BETWEEN -180 AND 180),
    dropoff_lat         double precision NOT NULL CHECK (dropoff_lat BETWEEN -90 AND 90),
    dropoff_lon         double precision NOT NULL CHECK (dropoff_lon BETWEEN -180 AND 180),
    pickup_zone         text             NOT NULL,
    distance_m          int              NOT NULL CHECK (distance_m >= 0),
    duration_s          int              NOT NULL CHECK (duration_s >= 0),
    route_source        text             NOT NULL CHECK (route_source IN ('MOCK', 'OSRM', 'MOCK_FALLBACK')),
    fare_rule_id        uuid             NOT NULL,
    fee_rule_id         uuid             NOT NULL,
    surge_multiplier    numeric(3, 2)    NOT NULL CHECK (surge_multiplier BETWEEN 1.00 AND 2.00),
    surge_source        text             NOT NULL CHECK (surge_source IN ('NONE', 'RULE', 'COMPUTED')),
    base_paise          bigint           NOT NULL CHECK (base_paise >= 0),
    distance_paise      bigint           NOT NULL CHECK (distance_paise >= 0),
    time_paise          bigint           NOT NULL CHECK (time_paise >= 0),
    surge_paise         bigint           NOT NULL CHECK (surge_paise >= 0),
    minimum_topup_paise bigint           NOT NULL CHECK (minimum_topup_paise >= 0),
    booking_fee_paise   bigint           NOT NULL CHECK (booking_fee_paise >= 0),
    tax_paise           bigint           NOT NULL CHECK (tax_paise >= 0),
    rounding_paise      bigint           NOT NULL CHECK (rounding_paise BETWEEN 0 AND 99),
    total_paise         bigint           NOT NULL CHECK (total_paise % 100 = 0),
    commission_paise    bigint           NOT NULL CHECK (commission_paise BETWEEN 0 AND total_paise - tax_paise),
    currency            char(3)          NOT NULL,
    pickup_eta_s        int              CHECK (pickup_eta_s >= 0),
    created_at          timestamptz      NOT NULL,
    expires_at          timestamptz      NOT NULL,
    used_by_ride_id     uuid             UNIQUE,
    used_at             timestamptz,
    CHECK (total_paise = base_paise + distance_paise + time_paise + surge_paise + minimum_topup_paise
                         + booking_fee_paise + tax_paise + rounding_paise),
    CHECK (expires_at > created_at),
    CHECK ((used_by_ride_id IS NULL) = (used_at IS NULL))
);

CREATE INDEX quotes_retention ON pricing.quotes (created_at);
-- Demand per zone for computed surge (V4).
CREATE INDEX quotes_by_zone ON pricing.quotes (city_id, pickup_zone, created_at);
