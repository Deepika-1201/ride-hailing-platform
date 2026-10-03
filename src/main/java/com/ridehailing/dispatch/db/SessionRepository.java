package com.ridehailing.dispatch.db;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Online sessions: one opens when a driver goes online and closes, with a reason, when they go offline. */
@Repository
public class SessionRepository {

    private final JdbcClient jdbc;

    SessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Starts at {@code now()}, the same instant as the availability row's {@code online_since}. */
    public void open(UUID id, UUID driverId, String cityId, UUID vehicleId, String category) {
        jdbc.sql("""
                        INSERT INTO dispatch.driver_sessions (id, driver_id, city_id, vehicle_id, category, online_at)
                        VALUES (:id, :driverId, :cityId, :vehicleId, :category, now())
                        """)
                .param("id", id)
                .param("driverId", driverId)
                .param("cityId", cityId)
                .param("vehicleId", vehicleId)
                .param("category", category)
                .update();
    }

    public void close(UUID driverId, String reason) {
        jdbc.sql("""
                        UPDATE dispatch.driver_sessions SET offline_at = now(), offline_reason = :reason
                        WHERE driver_id = :driverId AND offline_at IS NULL
                        """)
                .param("driverId", driverId)
                .param("reason", reason)
                .update();
    }
}
