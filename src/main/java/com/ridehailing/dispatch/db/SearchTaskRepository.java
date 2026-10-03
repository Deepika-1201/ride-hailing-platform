package com.ridehailing.dispatch.db;

import com.ridehailing.shared.GeoPoint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * One search task per searching ride (LLD §4.6, §8.3). Pollers claim them with {@code SKIP LOCKED}; everyone else
 * removes them without waiting (§6.2).
 */
@Repository
public class SearchTaskRepository {

    private static final String COLUMNS = """
            SELECT ride_id, city_id, category, pickup_lat, pickup_lon, priority, attempt, radius_m, backoff_s
            FROM dispatch.search_tasks
            """;

    private final JdbcClient jdbc;

    SearchTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Due now; a task left from an earlier search of the ride is reset. */
    public void start(UUID rideId, String cityId, String category, GeoPoint pickup, int priority, int radiusM) {
        jdbc.sql("""
                        INSERT INTO dispatch.search_tasks (ride_id, city_id, category, pickup_lat, pickup_lon, priority,
                                                           radius_m, due_at, created_at, updated_at)
                        VALUES (:rideId, :cityId, :category, :lat, :lon, :priority, :radiusM, now(), now(), now())
                        ON CONFLICT (ride_id) DO UPDATE
                        SET priority = EXCLUDED.priority, radius_m = EXCLUDED.radius_m, due_at = now(),
                            backoff_s = 0, updated_at = now()
                        """)
                .param("rideId", rideId)
                .param("cityId", cityId)
                .param("category", category)
                .param("lat", pickup.lat())
                .param("lon", pickup.lon())
                .param("priority", priority)
                .param("radiusM", radiusM)
                .update();
    }

    /** The most urgent due task no other poller holds, locked until the transaction ends. */
    public Optional<TaskRow> claimDue() {
        return jdbc.sql(COLUMNS + """
                        WHERE due_at <= clock_timestamp()
                        ORDER BY priority DESC, due_at
                        LIMIT 1 FOR UPDATE SKIP LOCKED
                        """)
                .query(SearchTaskRepository::task)
                .optional();
    }

    /** Deletes the task the caller holds. */
    public void delete(UUID rideId) {
        jdbc.sql("DELETE FROM dispatch.search_tasks WHERE ride_id = :rideId").param("rideId", rideId).update();
    }

    /** Deletes the task unless a poller holds it now; that poller then finds the ride moved on (§6.2). */
    public void deleteWithoutWaiting(UUID rideId) {
        jdbc.sql("""
                        DELETE FROM dispatch.search_tasks WHERE ride_id IN (
                            SELECT ride_id FROM dispatch.search_tasks WHERE ride_id = :rideId FOR UPDATE SKIP LOCKED)
                        """)
                .param("rideId", rideId)
                .update();
    }

    /** While the attempt's offer is pending. */
    public void pause(UUID rideId) {
        jdbc.sql("""
                        UPDATE dispatch.search_tasks
                        SET due_at = NULL, attempt = attempt + 1, backoff_s = 0, updated_at = now()
                        WHERE ride_id = :rideId
                        """)
                .param("rideId", rideId)
                .update();
    }

    /** After an attempt that reserved nobody, at the given radius. */
    public void retryLater(UUID rideId, int radiusM, Duration delay) {
        jdbc.sql("""
                        UPDATE dispatch.search_tasks
                        SET attempt = attempt + 1, radius_m = :radiusM, backoff_s = 0,
                            due_at = clock_timestamp() + make_interval(secs => :delayS), updated_at = now()
                        WHERE ride_id = :rideId
                        """)
                .param("radiusM", radiusM)
                .param("delayS", delay.toMillis() / 1000.0)
                .param("rideId", rideId)
                .update();
    }

    /** After the live index failed: due again in {@code backoffS} seconds (dispatch §12). */
    public void backOff(UUID rideId, int backoffS) {
        jdbc.sql("""
                        UPDATE dispatch.search_tasks
                        SET attempt = attempt + 1, backoff_s = :backoffS,
                            due_at = clock_timestamp() + make_interval(secs => :backoffS), updated_at = now()
                        WHERE ride_id = :rideId
                        """)
                .param("backoffS", backoffS)
                .param("rideId", rideId)
                .update();
    }

    /**
     * After an offer ends without acceptance. It can't wait: a task is paused, so no poller holds it, while its offer
     * is pending (§6.2).
     */
    public void makeDue(UUID rideId) {
        jdbc.sql("UPDATE dispatch.search_tasks SET due_at = now(), updated_at = now() WHERE ride_id = :rideId")
                .param("rideId", rideId)
                .update();
    }

    private static TaskRow task(ResultSet row, int rowNumber) throws SQLException {
        return new TaskRow(row.getObject("ride_id", UUID.class), row.getString("city_id"), row.getString("category"),
                new GeoPoint(row.getDouble("pickup_lat"), row.getDouble("pickup_lon")), row.getInt("priority"),
                row.getInt("attempt"), row.getInt("radius_m"), row.getInt("backoff_s"));
    }

    public record TaskRow(UUID rideId, String cityId, String category, GeoPoint pickup, int priority, int attempt,
            int radiusM, int backoffS) {
    }
}
