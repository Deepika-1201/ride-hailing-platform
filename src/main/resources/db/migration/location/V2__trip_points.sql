-- Trip routes (LLD §4.8, §9.8, ADR-016), partitioned by the UTC day the points arrived. The writer sets received_day,
-- since a generated column can't be a partition key. The key starts with the ride, so reading a route probes each
-- partition's key. The default partition takes the points of days the maintenance job hadn't prepared; this migration
-- prepares today and the next two days, so writes find their partition before the job first runs.

CREATE TABLE location.trip_points (
    received_day date             NOT NULL,
    ride_id      uuid             NOT NULL,
    seq          bigint           NOT NULL,
    driver_id    uuid             NOT NULL,
    received_at  timestamptz      NOT NULL,
    device_time  timestamptz,
    lat          double precision NOT NULL,
    lon          double precision NOT NULL,
    accuracy_m   real,
    speed_mps    real,
    heading_deg  real,
    flags        smallint         NOT NULL DEFAULT 0,          -- 1 poor accuracy, 2 implausible, 4 replayed
    PRIMARY KEY (ride_id, seq, received_day)
) PARTITION BY RANGE (received_day);

CREATE TABLE location.trip_points_default PARTITION OF location.trip_points DEFAULT;

DO
$$
DECLARE
    today date := (now() AT TIME ZONE 'UTC')::date;
BEGIN
    FOR ahead IN 0..2 LOOP
        EXECUTE format('CREATE TABLE location.%I PARTITION OF location.trip_points FOR VALUES FROM (%L) TO (%L)',
                       'trip_points_' || to_char(today + ahead, 'YYYYMMDD'), today + ahead, today + ahead + 1);
    END LOOP;
END
$$;
