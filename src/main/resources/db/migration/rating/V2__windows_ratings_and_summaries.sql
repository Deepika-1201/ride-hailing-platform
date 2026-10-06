-- Rating windows, ratings and rolling summaries (LLD §4.7, §13.4).

CREATE TABLE rating.rating_windows (
    ride_id      uuid        PRIMARY KEY,
    rider_id     uuid        NOT NULL,
    driver_id    uuid        NOT NULL,
    completed_at timestamptz NOT NULL,
    closes_at    timestamptz NOT NULL
);

CREATE TABLE rating.ratings (
    id         uuid        PRIMARY KEY,
    ride_id    uuid        NOT NULL REFERENCES rating.rating_windows (ride_id),
    rater_role text        NOT NULL CHECK (rater_role IN ('RIDER', 'DRIVER')),
    rater_id   uuid        NOT NULL,
    ratee_id   uuid        NOT NULL,
    stars      smallint    NOT NULL CHECK (stars BETWEEN 1 AND 5),
    comment    text        CHECK (length(comment) <= 500),
    created_at timestamptz NOT NULL,
    UNIQUE (ride_id, rater_role)                              -- FR-RT1: once per side
);

CREATE INDEX ratings_by_ratee ON rating.ratings (ratee_id, created_at DESC);

-- The average of the latest 100 ratings (FR-RT2), kept in the rating's transaction.
CREATE TABLE rating.summaries (
    user_id    uuid         NOT NULL,
    party      text         NOT NULL CHECK (party IN ('RIDER', 'DRIVER')),
    average    numeric(3,2) NOT NULL,
    count      int          NOT NULL CHECK (count BETWEEN 0 AND 100),
    updated_at timestamptz  NOT NULL,
    PRIMARY KEY (user_id, party)
);
