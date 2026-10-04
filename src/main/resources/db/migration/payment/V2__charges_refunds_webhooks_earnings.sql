-- Charges, attempts, refunds, provider webhooks and driver earnings (LLD §4.7, §11, ADR-014).

CREATE TABLE payment.charges (
    id                   uuid        PRIMARY KEY,
    ride_id              uuid        NOT NULL,
    rider_id             uuid        NOT NULL,
    driver_id            uuid,
    city_id              text        NOT NULL,
    purpose              text        NOT NULL CHECK (purpose IN ('FARE', 'CANCELLATION_FEE', 'NO_SHOW_FEE')),
    amount_paise         bigint      NOT NULL CHECK (amount_paise > 0),
    commission_paise     bigint      NOT NULL CHECK (commission_paise BETWEEN 0 AND amount_paise),
    currency             char(3)     NOT NULL,
    method_type          text        NOT NULL CHECK (method_type IN ('CASH', 'CARD', 'UPI')),
    payment_method_id    uuid,                                -- null while no usable method exists
    status               text        NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    failure_code         text,
    succeeded_attempt_id uuid,                                -- null for cash
    refunded_paise       bigint      NOT NULL DEFAULT 0,      -- reserved by non-failed operations refunds
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    version              int         NOT NULL DEFAULT 0,
    UNIQUE (ride_id, purpose),                                -- I7: one charge per ride and purpose
    CHECK (refunded_paise BETWEEN 0 AND amount_paise),
    CHECK ((status = 'FAILED') = (failure_code IS NOT NULL))
);

CREATE INDEX charges_dues ON payment.charges (rider_id) WHERE status = 'FAILED';
CREATE INDEX charges_by_status ON payment.charges (status, updated_at) WHERE status IN ('FAILED', 'UNKNOWN');

CREATE TABLE payment.charge_attempts (
    id                  uuid        PRIMARY KEY,              -- also the provider's idempotency key
    charge_id           uuid        NOT NULL REFERENCES payment.charges (id),
    seq                 smallint    NOT NULL,
    status              text        NOT NULL CHECK (status IN ('PENDING', 'IN_FLIGHT', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    provider            text        NOT NULL,
    payment_method_id   uuid        NOT NULL,
    method_ref          text        NOT NULL,                 -- the method's provider token, captured with the attempt
    provider_payment_id text,
    failure_code        text,
    created_at          timestamptz NOT NULL,
    sent_at             timestamptz,
    completed_at        timestamptz,
    lease_until         timestamptz,                          -- while IN_FLIGHT
    next_check_at       timestamptz,                          -- while UNKNOWN; null once checks stop (24 h)
    checks              smallint    NOT NULL DEFAULT 0,
    version             int         NOT NULL DEFAULT 0,
    UNIQUE (charge_id, seq),
    CHECK ((status = 'FAILED') = (failure_code IS NOT NULL)),
    CHECK ((status = 'IN_FLIGHT') = (lease_until IS NOT NULL))
);

CREATE UNIQUE INDEX one_open_attempt_per_charge ON payment.charge_attempts (charge_id)
    WHERE status IN ('PENDING', 'IN_FLIGHT', 'UNKNOWN');
CREATE INDEX attempts_to_send ON payment.charge_attempts (created_at) WHERE status = 'PENDING';
CREATE INDEX attempts_in_flight ON payment.charge_attempts (lease_until) WHERE status = 'IN_FLIGHT';
CREATE INDEX attempts_to_check ON payment.charge_attempts (next_check_at) WHERE status = 'UNKNOWN';

CREATE TABLE payment.refunds (
    id                 uuid        PRIMARY KEY,               -- also the provider's idempotency key
    charge_id          uuid        NOT NULL REFERENCES payment.charges (id),
    attempt_id         uuid        NOT NULL REFERENCES payment.charge_attempts (id),  -- the payment refunded
    amount_paise       bigint      NOT NULL CHECK (amount_paise > 0),
    reason             text        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    automatic          boolean     NOT NULL DEFAULT false,    -- late-success refunds
    status             text        NOT NULL CHECK (status IN ('PENDING', 'IN_FLIGHT', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    failure_code       text,
    provider_refund_id text,
    requested_by       text        NOT NULL,
    created_at         timestamptz NOT NULL,
    sent_at            timestamptz,
    completed_at       timestamptz,
    lease_until        timestamptz,
    next_check_at      timestamptz,
    checks             smallint    NOT NULL DEFAULT 0,
    version            int         NOT NULL DEFAULT 0,
    CHECK ((status = 'FAILED') = (failure_code IS NOT NULL)),
    CHECK ((status = 'IN_FLIGHT') = (lease_until IS NOT NULL))
);

CREATE INDEX refunds_by_charge ON payment.refunds (charge_id);
CREATE UNIQUE INDEX one_automatic_refund_per_attempt ON payment.refunds (attempt_id) WHERE automatic;
CREATE INDEX refunds_to_send ON payment.refunds (created_at) WHERE status = 'PENDING';
CREATE INDEX refunds_in_flight ON payment.refunds (lease_until) WHERE status = 'IN_FLIGHT';
CREATE INDEX refunds_to_check ON payment.refunds (next_check_at) WHERE status = 'UNKNOWN';

CREATE TABLE payment.provider_webhooks (
    provider          text        NOT NULL,
    provider_event_id text        NOT NULL,
    event_type        text        NOT NULL,
    received_at       timestamptz NOT NULL,
    raw_body          text        NOT NULL,                   -- byte-exact, as signed
    processed_at      timestamptz,
    outcome           text CHECK (outcome IN ('APPLIED', 'IGNORED', 'UNMATCHED')),
    PRIMARY KEY (provider, provider_event_id)
);

-- A read model (ADR-014): rows are unique per source and kind, so redelivered events don't count twice.
CREATE TABLE payment.driver_earnings (
    id                   uuid        PRIMARY KEY,
    driver_id            uuid        NOT NULL,
    ride_id              uuid        NOT NULL,
    kind                 text        NOT NULL CHECK (kind IN ('FARE', 'CANCELLATION_FEE', 'NO_SHOW_FEE', 'ADJUSTMENT')),
    source_id            uuid        NOT NULL,                -- ride (fare), fee charge, or refund (adjustment)
    gross_paise          bigint      NOT NULL,
    commission_paise     bigint      NOT NULL,
    net_paise            bigint      NOT NULL,
    cash_collected_paise bigint      NOT NULL DEFAULT 0,
    currency             char(3)     NOT NULL,
    earned_at            timestamptz NOT NULL,
    earned_on            date        NOT NULL,                -- in the city's time zone
    UNIQUE (source_id, kind),
    CHECK (net_paise = gross_paise - commission_paise)
);

CREATE INDEX earnings_by_driver ON payment.driver_earnings (driver_id, earned_on);
