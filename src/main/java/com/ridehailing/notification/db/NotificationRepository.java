package com.ridehailing.notification.db;

import com.ridehailing.notification.NotificationApi.DeliveryView;
import com.ridehailing.notification.NotificationApi.NotificationView;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** The ride's notifications with their deliveries, oldest first. */
    public List<NotificationView> ofRide(UUID rideId) {
        Map<UUID, NotificationView> byId = new LinkedHashMap<>();
        Map<UUID, List<DeliveryView>> deliveries = new HashMap<>();
        jdbc.sql("""
                        SELECT n.id, n.recipient_id, n.kind, n.created_at, d.channel, d.status, d.attempts, d.sent_at,
                               d.last_error
                        FROM notification.notifications n
                        LEFT JOIN notification.deliveries d ON d.notification_id = n.id
                        WHERE n.ride_id = :rideId
                        ORDER BY n.created_at, n.id, d.id
                        """)
                .param("rideId", rideId)
                .query(row -> {
                    UUID id = row.getObject("id", UUID.class);
                    if (!byId.containsKey(id)) {
                        byId.put(id, new NotificationView(id, row.getObject("recipient_id", UUID.class),
                                row.getString("kind"), row.getObject("created_at", OffsetDateTime.class).toInstant(),
                                List.of()));
                    }
                    String channel = row.getString("channel");
                    if (channel != null) {
                        OffsetDateTime sentAt = row.getObject("sent_at", OffsetDateTime.class);
                        deliveries.computeIfAbsent(id, key -> new ArrayList<>()).add(new DeliveryView(channel,
                                row.getString("status"), row.getInt("attempts"),
                                sentAt == null ? null : sentAt.toInstant(), row.getString("last_error")));
                    }
                });
        return byId.values().stream().map(notification -> new NotificationView(notification.id(),
                notification.recipientId(), notification.kind(), notification.createdAt(),
                deliveries.getOrDefault(notification.id(), List.of()))).toList();
    }
}
