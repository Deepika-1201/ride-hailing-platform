-- Fare, fee and surge rules (LLD §4.4, §10.5). Fare and fee rules are versioned and never updated (ADR-012).

CREATE TABLE pricing.fare_rules (
    id                uuid        PRIMARY KEY,
    city_id           text        NOT NULL,
    category          text        NOT NULL,
    version           int         NOT NULL CHECK (version >= 1),
    effective_from    timestamptz NOT NULL,
    base_paise        bigint      NOT NULL CHECK (base_paise >= 0),
    per_km_paise      bigint      NOT NULL CHECK (per_km_paise >= 0),
    per_min_paise     bigint      NOT NULL CHECK (per_min_paise >= 0),
    minimum_paise     bigint      NOT NULL CHECK (minimum_paise >= 0),
    booking_fee_paise bigint      NOT NULL CHECK (booking_fee_paise >= 0),
    tax_bp            int         NOT NULL CHECK (tax_bp BETWEEN 0 AND 5000),
    commission_bp     int         NOT NULL CHECK (commission_bp BETWEEN 0 AND 5000),
    currency          char(3)     NOT NULL,
    created_by        uuid        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (city_id, category, version)
);

CREATE INDEX fare_rules_current ON pricing.fare_rules (city_id, category, effective_from DESC);

CREATE TABLE pricing.fee_rules (
    id                     uuid        PRIMARY KEY,
    city_id                text        NOT NULL,
    category               text        NOT NULL,
    version                int         NOT NULL CHECK (version >= 1),
    effective_from         timestamptz NOT NULL,
    cancellation_fee_paise bigint      NOT NULL CHECK (cancellation_fee_paise >= 0),
    no_show_fee_paise      bigint      NOT NULL CHECK (no_show_fee_paise >= 0),
    free_cancel_window_s   int         NOT NULL DEFAULT 120 CHECK (free_cancel_window_s >= 0),
    late_grace_s           int         NOT NULL DEFAULT 300 CHECK (late_grace_s >= 0),
    pickup_wait_s          int         NOT NULL DEFAULT 300 CHECK (pickup_wait_s >= 0),
    commission_bp          int         NOT NULL CHECK (commission_bp BETWEEN 0 AND 5000),
    currency               char(3)     NOT NULL,
    created_by             uuid        NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT now(),
    UNIQUE (city_id, category, version)
);

CREATE INDEX fee_rules_current ON pricing.fee_rules (city_id, category, effective_from DESC);

-- A published version is never changed, so the price in effect at any moment never changes after the fact (ADR-012).
CREATE FUNCTION pricing.reject_rule_change() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% versions are never changed: % is not allowed', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TRIGGER fare_rules_immutable BEFORE UPDATE OR DELETE ON pricing.fare_rules
    FOR EACH ROW EXECUTE FUNCTION pricing.reject_rule_change();
CREATE TRIGGER fee_rules_immutable BEFORE UPDATE OR DELETE ON pricing.fee_rules
    FOR EACH ROW EXECUTE FUNCTION pricing.reject_rule_change();

CREATE TABLE pricing.surge_rules (
    id           uuid         PRIMARY KEY,
    city_id      text         NOT NULL,
    zone_id      text         NOT NULL,
    days_of_week smallint[]   NOT NULL
        CHECK (days_of_week <@ ARRAY[1, 2, 3, 4, 5, 6, 7]::smallint[] AND cardinality(days_of_week) > 0),
    start_local  time         NOT NULL,
    end_local    time         NOT NULL CHECK (end_local <> start_local),
    multiplier   numeric(3, 2) NOT NULL CHECK (multiplier BETWEEN 1.00 AND 2.00),
    active       boolean      NOT NULL DEFAULT true,
    created_by   uuid         NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    version      int          NOT NULL DEFAULT 0
);

CREATE INDEX surge_rules_by_zone ON pricing.surge_rules (city_id, zone_id) WHERE active;
