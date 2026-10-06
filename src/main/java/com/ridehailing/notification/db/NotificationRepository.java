package com.ridehailing.notification.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Notifications, one per event, recipient and kind (LLD §4.7, §15.4). */
@Repository
public class NotificationRepository {

    private final JdbcClient jdbc;

    NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The new notification's ID; empty if the event already made this one, even in a concurrent transaction. */
    public Optional<UUID> insert(UUID id, UUID recipientId, String kind, UUID rideId, UUID eventId, String payload) {
        return jdbc.sql("""
                        INSERT INTO notification.notifications (id, recipient_id, kind, ride_id, event_id, payload,
                            created_at)
                        VALUES (:id, :recipientId, :kind, :rideId, :eventId, CAST(:payload AS jsonb), now())
                        ON CONFLICT (event_id, recipient_id, kind) DO NOTHING
                        RETURNING id
                        """)
                .param("id", id)
                .param("recipientId", recipientId)
                .param("kind", kind)
                .param("rideId", rideId)
                .param("eventId", eventId)
                .param("payload", payload)
                .query(UUID.class)
                .optional();
    }
}
