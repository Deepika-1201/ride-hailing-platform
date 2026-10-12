CREATE INDEX quotes_surge_created ON pricing.quotes (city_id, created_at) INCLUDE (pickup_zone);
CREATE INDEX quotes_surge_used ON pricing.quotes (city_id, used_at) INCLUDE (pickup_zone) WHERE used_at IS NOT NULL;

CREATE TABLE pricing.surge_runs (
    city_id text PRIMARY KEY,
    sampled_at timestamptz NOT NULL,
    computed_at timestamptz NOT NULL,
    owner_holder text NOT NULL,
    owner_token bigint NOT NULL CHECK (owner_token > 0)
);

CREATE TABLE pricing.surge_multipliers (
    city_id text NOT NULL,
    zone_id text NOT NULL,
    multiplier numeric(3, 2) NOT NULL CHECK (multiplier BETWEEN 1.00 AND 2.00),
    sampled_at timestamptz NOT NULL,
    computed_at timestamptz NOT NULL,
    PRIMARY KEY (city_id, zone_id)
);

CREATE TABLE pricing.surge_history (
    city_id text NOT NULL,
    zone_id text NOT NULL,
    sampled_at timestamptz NOT NULL,
    computed_at timestamptz NOT NULL,
    multiplier numeric(3, 2) NOT NULL CHECK (multiplier BETWEEN 1.00 AND 2.00),
    target numeric(3, 2) NOT NULL CHECK (target BETWEEN 1.00 AND 2.00),
    rides bigint NOT NULL CHECK (rides >= 0),
    quotes bigint NOT NULL CHECK (quotes >= 0),
    available bigint NOT NULL CHECK (available >= 0),
    neighboring bigint NOT NULL CHECK (neighboring >= 0),
    demand numeric(22, 2) NOT NULL CHECK (demand BETWEEN 0 AND 99999999999999999999.99),
    supply numeric(22, 2) NOT NULL CHECK (supply BETWEEN 0 AND 99999999999999999999.99),
    steps jsonb NOT NULL CHECK (jsonb_typeof(steps) = 'array'),
    owner_holder text NOT NULL,
    owner_token bigint NOT NULL CHECK (owner_token > 0),
    PRIMARY KEY (city_id, zone_id, computed_at)
);

CREATE INDEX surge_history_retention ON pricing.surge_history (computed_at);