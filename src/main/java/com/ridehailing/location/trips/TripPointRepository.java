package com.ridehailing.location.trips;

import com.ridehailing.location.TripRoutes.RoutePoint;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code location.trip_points} (LLD §4.8): appended in batches, read per ride. */
@Repository
public class TripPointRepository {

    private static final String INSERT = """
            INSERT INTO location.trip_points (received_day, ride_id, seq, driver_id, received_at, device_time, lat, lon,
                                              accuracy_m, speed_mps, heading_deg, flags)
            VALUES\s""";
    private static final String ROW = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcClient jdbc;

    TripPointRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One statement; a point already stored for its day, ride and sequence number is skipped (a replay). */
    public void insert(List<TripPoint> points) {
        StringBuilder sql = new StringBuilder(INSERT);
        List<Object> values = new ArrayList<>(points.size() * 12);
        for (TripPoint point : points) {
            sql.append(values.isEmpty() ? "" : ", ").append(ROW);
            values.add(LocalDate.ofInstant(point.receivedAt(), ZoneOffset.UTC));
            values.add(point.rideId());
            values.add(point.seq());
            values.add(point.driverId());
            values.add(utc(point.receivedAt()));
            values.add(utc(point.deviceTime()));
            values.add(point.lat());
            values.add(point.lon());
            values.add(point.accuracyM());
            values.add(point.speedMps());
            values.add(point.headingDeg());
            values.add(point.flags());
        }
        jdbc.sql(sql.append(" ON CONFLICT DO NOTHING").toString()).params(values).update();
    }

    /**
     * The ride's points in sequence order. A replay sent again on a later day is stored again in that day's
     * partition, so each sequence number keeps its first arrival.
     */
    public List<RoutePoint> route(UUID rideId) {
        return jdbc.sql("""
                        SELECT DISTINCT ON (seq) seq, lat, lon, received_at FROM location.trip_points
                        WHERE ride_id = :rideId
                        ORDER BY seq, received_at
                        """)
                .param("rideId", rideId)
                .query((row, n) -> new RoutePoint(row.getLong("seq"), row.getDouble("lat"), row.getDouble("lon"),
                        row.getObject("received_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
