package com.ridehailing.notification.app;

import com.ridehailing.notification.db.DeliveryRepository;
import com.ridehailing.notification.push.NotificationProvider;
import com.ridehailing.platform.Transactions;
import com.ridehailing.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Notifications and their deliveries as rows, executors in front of scripted providers, and the delivery counters.
 * Other tests' pending deliveries are put off a day first, so an executor sends this test's alone.
 */
abstract class NotificationTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Transactions transactions;

    @Autowired
    DeliveryRepository deliveries;

    @Autowired
    NotificationMetrics metrics;

    @Autowired
    NotificationProperties properties;

    @Autowired
    JsonMapper json;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void putOffOtherDeliveries() {
        jdbc.sql("""
                UPDATE notification.deliveries SET next_attempt_at = now() + interval '1 day'
                WHERE status = 'PENDING'
                """).update();
    }

    DeliveryExecutor executorWith(NotificationProvider provider) {
        return new DeliveryExecutor(deliveries, provider, metrics, transactions, properties, json);
    }

    /** Sends until nothing is due; answers how many sends ran. */
    static int sendAll(DeliveryExecutor executor) {
        int sends = 0;
        while (executor.sendNext()) {
            sends++;
        }
        return sends;
    }

    /** The event's notifications, each with its one delivery, ordered by kind and recipient. */
    List<Told> toldBy(UUID eventId) {
        return told("n.event_id = :id", eventId);
    }

    /** The ride's notifications, each with its one delivery, ordered by kind and recipient. */
    List<Told> toldAbout(UUID rideId) {
        return told("n.ride_id = :id", rideId);
    }

    private List<Told> told(String condition, UUID id) {
        return jdbc.sql("""
                        SELECT n.recipient_id, n.kind, n.ride_id, n.payload::text AS payload, d.id AS delivery_id,
                               d.channel, d.status, d.attempts, d.last_error,
                               CAST(EXTRACT(EPOCH FROM d.next_attempt_at - now()) * 1000 AS bigint) AS due_in_ms,
                               (SELECT count(*) FROM notification.deliveries o WHERE o.notification_id = n.id)
                                   AS deliveries
                        FROM notification.notifications n JOIN notification.deliveries d ON d.notification_id = n.id
                        WHERE\s""" + condition + " ORDER BY n.kind, n.recipient_id")
                .param("id", id)
                .query((row, rowNumber) -> new Told(row.getObject("recipient_id", UUID.class), row.getString("kind"),
                        row.getObject("ride_id", UUID.class), json.readTree(row.getString("payload")),
                        row.getObject("delivery_id", UUID.class), row.getString("channel"), row.getString("status"),
                        row.getInt("attempts"), row.getString("last_error"),
                        Duration.ofMillis(row.getLong("due_in_ms")), row.getInt("deliveries")))
                .list();
    }

    Told delivery(UUID deliveryId) {
        UUID eventId = jdbc.sql("""
                        SELECT n.event_id FROM notification.deliveries d
                        JOIN notification.notifications n ON n.id = d.notification_id WHERE d.id = :id
                        """)
                .param("id", deliveryId)
                .query(UUID.class)
                .single();
        return toldBy(eventId).stream().filter(told -> told.deliveryId().equals(deliveryId)).findFirst().orElseThrow();
    }

    /** As if the delivery's wait, or the lease of a send in progress, had passed. */
    void dueNow(UUID deliveryId) {
        jdbc.sql("UPDATE notification.deliveries SET next_attempt_at = now() WHERE id = :id")
                .param("id", deliveryId)
                .update();
    }

    double counted(String outcome) {
        return meters.counter("notification.deliveries", "channel", "PUSH", "outcome", outcome).count();
    }

    /** A notification and its delivery; {@code dueIn} is negative once the delivery is due. */
    record Told(UUID recipientId, String kind, UUID rideId, JsonNode payload, UUID deliveryId, String channel,
            String status, int attempts, String lastError, Duration dueIn, int deliveries) {
    }
}
