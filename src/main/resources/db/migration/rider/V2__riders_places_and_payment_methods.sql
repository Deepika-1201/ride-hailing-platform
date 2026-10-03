-- Rider profiles, saved places and payment methods (LLD §4.3).

CREATE TABLE rider.riders (
    user_id                   uuid        PRIMARY KEY,
    first_name                text        CHECK (length(first_name) BETWEEN 1 AND 60),
    last_name                 text        CHECK (length(last_name) <= 60),
    email                     text        CHECK (length(email) <= 200),
    default_payment_method_id uuid,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now(),
    version                   int         NOT NULL DEFAULT 0
);

CREATE TABLE rider.saved_places (
    id         uuid             PRIMARY KEY,
    rider_id   uuid             NOT NULL REFERENCES rider.riders (user_id),
    label      text             NOT NULL CHECK (length(label) BETWEEN 1 AND 40),
    name       text             NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    lat        double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lon        double precision NOT NULL CHECK (lon BETWEEN -180 AND 180),
    created_at timestamptz      NOT NULL DEFAULT now(),
    UNIQUE (rider_id, label)
);

CREATE TABLE rider.payment_methods (
    id           uuid        PRIMARY KEY,
    rider_id     uuid        NOT NULL REFERENCES rider.riders (user_id),
    type         text        NOT NULL CHECK (type IN ('CASH', 'CARD', 'UPI')),
    provider_ref text,
    display      text        NOT NULL CHECK (length(display) BETWEEN 1 AND 40),
    active       boolean     NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CHECK ((type = 'CASH') = (provider_ref IS NULL)),
    CHECK (type <> 'CASH' OR active),
    UNIQUE (rider_id, id)
);

CREATE UNIQUE INDEX one_cash_method ON rider.payment_methods (rider_id) WHERE type = 'CASH';
CREATE INDEX payment_methods_by_rider ON rider.payment_methods (rider_id, created_at);

-- The default is one of the rider's own methods; checked at commit, since the rider row comes first.
ALTER TABLE rider.riders
    ADD CONSTRAINT default_payment_method_fk FOREIGN KEY (user_id, default_payment_method_id)
        REFERENCES rider.payment_methods (rider_id, id) DEFERRABLE INITIALLY DEFERRED;
