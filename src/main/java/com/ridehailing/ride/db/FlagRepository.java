package com.ridehailing.ride.db;

import com.ridehailing.shared.Ids;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The operations review queue (LLD §7.11); listing and resolving arrive in phase 11. */
@Repository
public class FlagRepository {

    private final JdbcClient jdbc;

    FlagRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Opens a flag unless one of the kind is open on the ride. */
    public void open(UUID rideId, FlagKind kind, String detailsJson) {
        jdbc.sql("""
                        INSERT INTO ride.flags (id, ride_id, kind, details, created_at)
                        VALUES (:id, :rideId, :kind, CAST(:details AS jsonb), now())
                        ON CONFLICT (ride_id, kind) WHERE resolved_at IS NULL DO NOTHING
                        """)
                .param("id", Ids.newId())
                .param("rideId", rideId)
                .param("kind", kind.name())
                .param("details", detailsJson)
                .update();
    }

    public enum FlagKind {
        ARRIVED_FAR,
        DRIVER_CANCELLED_AT_PICKUP,
        PIN_LOCKED,
        STUCK
    }
}
