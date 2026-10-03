package com.ridehailing.ride.db;

import com.ridehailing.ride.RideStatus;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The transition log: one row per ride version after booking (LLD §4.5). */
@Repository
public class TransitionRepository {

    private final JdbcClient jdbc;

    TransitionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code from} is null for the booking; {@code actorId} and {@code reason} may be null. */
    public void insert(UUID id, UUID rideId, int version, RideStatus from, RideStatus to, String command,
            String actorType, String actorId, String reason, String requestId) {
        jdbc.sql("""
                        INSERT INTO ride.transitions (id, ride_id, version, from_status, to_status, command, actor_type,
                                                      actor_id, reason, occurred_at, request_id)
                        VALUES (:id, :rideId, :version, :from, :to, :command, :actorType, :actorId, :reason, now(),
                                :requestId)
                        """)
                .param("id", id)
                .param("rideId", rideId)
                .param("version", version)
                .param("from", from == null ? null : from.name())
                .param("to", to.name())
                .param("command", command)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("reason", reason)
                .param("requestId", requestId)
                .update();
    }
}
