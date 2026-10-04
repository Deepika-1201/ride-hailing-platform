package com.ridehailing.dispatch.db;

import java.util.Locale;
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

    /** Columns of {@code dispatch.driver_stats}. */
    public enum Stat {
        OFFERS,
        ACCEPTED,
        DECLINED,
        EXPIRED,
        CANCELLED_AFTER_ACCEPT
    }
}
