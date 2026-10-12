package com.ridehailing.dispatch.db;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Offer counts per driver, for V4 ranking (LLD §4.6); a driver's row appears with their first offer. */
@Repository
public class DriverStatsRepository {

    private final JdbcClient jdbc;

    DriverStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void count(UUID driverId, Stat stat) {
        String column = stat.name().toLowerCase(Locale.ROOT);
        jdbc.sql("""
                        INSERT INTO dispatch.driver_stats (driver_id, %1$s, updated_at) VALUES (:driverId, 1, now())
                        ON CONFLICT (driver_id) DO UPDATE
                        SET %1$s = dispatch.driver_stats.%1$s + 1, updated_at = now()
                        """.formatted(column))
                .param("driverId", driverId)
                .update();
    }

    public Map<UUID, RankingStats> rankingInputs(String cityId, Collection<UUID> drivers) {
        if (drivers.isEmpty()) {
            return Map.of();
        }
        if (drivers.size() > 10) {
            throw new IllegalArgumentException("Ranking statistics are limited to ten candidate drivers");
        }
        Map<UUID, RankingStats> result = new HashMap<>();
        jdbc.sql("""
                SELECT availability.driver_id, coalesce(stats.accepted, 0) AS accepted,
                       coalesce(stats.declined, 0) AS declined, coalesce(stats.expired, 0) AS expired,
                       CASE WHEN availability.status = 'AVAILABLE' THEN
                           greatest(extract(epoch FROM clock_timestamp() - availability.status_changed_at) / 60.0, 0)
                           ELSE 0 END AS idle_minutes
                FROM dispatch.driver_availability availability
                LEFT JOIN dispatch.driver_stats stats ON stats.driver_id = availability.driver_id
                WHERE availability.city_id = :city AND availability.driver_id = ANY(:drivers)
                """)
                .param("city", cityId).param("drivers", drivers.toArray(UUID[]::new))
                .query((row, rowNumber) -> Map.entry(row.getObject("driver_id", UUID.class),
                        new RankingStats(row.getLong("accepted"), row.getLong("declined"), row.getLong("expired"),
                                row.getDouble("idle_minutes"))))
                .list().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    public record RankingStats(long accepted, long declined, long expired, double idleMinutes) {
        public static final RankingStats NEW = new RankingStats(0, 0, 0, 0);
    }

    /** Columns of {@code dispatch.driver_stats}. */
    public enum Stat {
        OFFERS,
        ACCEPTED,
        DECLINED,
        EXPIRED,
        CANCELLED_AFTER_ACCEPT
    }
}
