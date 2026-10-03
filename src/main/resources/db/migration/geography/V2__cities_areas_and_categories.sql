-- PostGIS, cities, service and special areas, categories and per-city category settings (LLD §4.4).
-- Creating the extension can take longer than the pool's statement timeout, so this migration lifts it (LLD §4.9).
SET LOCAL statement_timeout = 0;
CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;
SET LOCAL search_path = geography, public;

CREATE TABLE geography.cities (
    id        text                   PRIMARY KEY CHECK (id ~ '^[a-z]{3,8}$'),
    name      text                   NOT NULL,
    time_zone text                   NOT NULL,
    currency  char(3)                NOT NULL,
    bounds    geometry(Polygon, 4326) NOT NULL CHECK (ST_IsValid(bounds)),
    active    boolean                NOT NULL DEFAULT true,
    version   int                    NOT NULL DEFAULT 0
);

CREATE TABLE geography.service_areas (
    id         uuid                         PRIMARY KEY,
    city_id    text                         NOT NULL REFERENCES geography.cities (id),
    area       geometry(MultiPolygon, 4326) NOT NULL CHECK (ST_IsValid(area)),
    active     boolean                      NOT NULL DEFAULT true,
    created_at timestamptz                  NOT NULL DEFAULT now()
);

CREATE INDEX service_areas_gist ON geography.service_areas USING gist (area) WHERE active;
CREATE UNIQUE INDEX one_active_service_area ON geography.service_areas (city_id) WHERE active;

CREATE TABLE geography.special_areas (
    id         uuid                         PRIMARY KEY,
    city_id    text                         NOT NULL REFERENCES geography.cities (id),
    code       text                         NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9-]{2,40}$'),
    name       text                         NOT NULL,
    kind       text                         NOT NULL CHECK (kind IN ('AIRPORT', 'STATION', 'STADIUM', 'OTHER')),
    area       geometry(MultiPolygon, 4326) NOT NULL CHECK (ST_IsValid(area)),
    priority   int                          NOT NULL DEFAULT 0,
    active     boolean                      NOT NULL DEFAULT true,
    created_at timestamptz                  NOT NULL DEFAULT now()
);

CREATE INDEX special_areas_gist ON geography.special_areas USING gist (area) WHERE active;

CREATE TABLE geography.categories (
    code  text     PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]{1,19}$'),
    name  text     NOT NULL,
    seats smallint NOT NULL CHECK (seats >= 1)
);

-- Reference data the code relies on; no endpoint creates categories.
INSERT INTO geography.categories (code, name, seats) VALUES
    ('AUTO', 'Auto rickshaw', 3),
    ('MINI', 'Mini', 4),
    ('SEDAN', 'Sedan', 4),
    ('XL', 'XL', 6);

CREATE TABLE geography.city_categories (
    city_id          text    NOT NULL REFERENCES geography.cities (id),
    category         text    NOT NULL REFERENCES geography.categories (code),
    active           boolean NOT NULL DEFAULT true,
    offer_ttl_s      int     NOT NULL DEFAULT 15 CHECK (offer_ttl_s BETWEEN 5 AND 60),
    search_timeout_s int     NOT NULL DEFAULT 180 CHECK (search_timeout_s BETWEEN 30 AND 900),
    radius_start_m   int     NOT NULL DEFAULT 2000 CHECK (radius_start_m >= 100),
    radius_step_m    int     NOT NULL DEFAULT 1000 CHECK (radius_step_m >= 0),
    radius_max_m     int     NOT NULL DEFAULT 6000 CHECK (radius_max_m >= 100),
    ranker           text    NOT NULL DEFAULT 'nearest' CHECK (ranker IN ('nearest', 'eta', 'weighted')),
    version          int     NOT NULL DEFAULT 0,
    PRIMARY KEY (city_id, category),
    CHECK (radius_start_m <= radius_max_m)
);
