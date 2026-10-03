-- Drivers, vehicles and the verification and suspension history (LLD §4.3).

CREATE TABLE driver.drivers (
    id                uuid        PRIMARY KEY,
    city_id           text        NOT NULL,
    first_name        text        NOT NULL CHECK (length(first_name) BETWEEN 1 AND 60),
    last_name         text        CHECK (length(last_name) <= 60),
    verification      text        NOT NULL DEFAULT 'PENDING' CHECK (verification IN ('PENDING', 'VERIFIED', 'REJECTED')),
    suspended         boolean     NOT NULL DEFAULT false,
    suspension_reason text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           int         NOT NULL DEFAULT 0
);

-- The admin list: newest first, optionally by city and verification.
CREATE INDEX drivers_by_city ON driver.drivers (city_id, verification, created_at DESC, id DESC);
CREATE INDEX drivers_newest ON driver.drivers (created_at DESC, id DESC);

CREATE TABLE driver.vehicles (
    id         uuid        PRIMARY KEY,
    driver_id  uuid        NOT NULL REFERENCES driver.drivers (id),
    category   text        NOT NULL,
    plate      text        NOT NULL UNIQUE CHECK (plate ~ '^[A-Z0-9 -]{4,15}$'),
    make       text        NOT NULL,
    model      text        NOT NULL,
    colour     text        NOT NULL,
    active     boolean     NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    version    int         NOT NULL DEFAULT 0
);

CREATE INDEX vehicles_by_driver ON driver.vehicles (driver_id);

CREATE TABLE driver.status_changes (
    id          uuid        PRIMARY KEY,
    driver_id   uuid        NOT NULL REFERENCES driver.drivers (id),
    kind        text        NOT NULL CHECK (kind IN ('VERIFICATION', 'SUSPENSION', 'REINSTATEMENT')),
    from_value  text,
    to_value    text        NOT NULL,
    reason      text,
    actor_id    text        NOT NULL,
    occurred_at timestamptz NOT NULL
);

CREATE INDEX status_changes_by_driver ON driver.status_changes (driver_id, occurred_at);
