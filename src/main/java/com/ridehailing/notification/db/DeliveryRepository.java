package com.ridehailing.notification.db;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Deliveries of notifications (LLD §15.4). A claim counts the attempt and leases the delivery by moving
 * {@code next_attempt_at} ahead; the answer is recorded in another transaction.
 */
@Repository
public class DeliveryRepository {

    private final JdbcClient jdbc;

    DeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Due at once. */
    public void insert(UUID id, UUID notificationId, String channel) {
        jdbc.sql("""
                        INSERT INTO notification.deliveries (id, notification_id, channel, status, next_attempt_at)
                        VALUES (:id, :notificationId, :channel, 'PENDING', now())
                        """)
                .param("id", id)
                .param("notificationId", notificationId)
                .param("channel", channel)
                .update();
    }

    /** The oldest due delivery, with its notification; never waits for a delivery another node holds. */
    public Optional<Claimed> claimDue(Duration lease) {
        return jdbc.sql("""
                        UPDATE notification.deliveries d
                        SET attempts = d.attempts + 1, next_attempt_at = now() + make_interval(secs => :leaseS)
                        FROM notification.notifications n
                        WHERE d.id = (SELECT id FROM notification.deliveries
                                      WHERE status = 'PENDING' AND next_attempt_at <= now()
                                      ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED)
                          AND n.id = d.notification_id
                        RETURNING d.id, d.channel, d.attempts, n.recipient_id, n.kind, n.ride_id, n.payload
                        """)
                .param("leaseS", lease.toSeconds())
                .query((row, rowNumber) -> new Claimed(row.getObject("id", UUID.class), row.getString("channel"),
                        row.getInt("attempts"), row.getObject("recipient_id", UUID.class), row.getString("kind"),
                        row.getObject("ride_id", UUID.class), row.getString("payload")))
                .optional();
    }

    /** False if it was already sent: by an earlier claim whose lease ran out. */
    public boolean sent(UUID id) {
        return jdbc.sql("""
                        UPDATE notification.deliveries SET status = 'SENT', sent_at = now()
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", id)
                .update() == 1;
    }

    /** False if a later claim took the delivery over, or it was sent. */
    public boolean retryAfter(UUID id, int attempt, String error, Duration delay) {
        return jdbc.sql("""
                        UPDATE notification.deliveries
                        SET next_attempt_at = now() + make_interval(secs => CAST(:delayS AS double precision)),
                            last_error = :error
                        WHERE id = :id AND status = 'PENDING' AND attempts = :attempt
                        """)
                .param("id", id)
                .param("attempt", attempt)
                .param("error", error)
                .param("delayS", delay.toMillis() / 1000.0)
                .update() == 1;
    }

    /** False if a later claim took the delivery over, or it was sent. */
    public boolean dead(UUID id, int attempt, String error) {
        return jdbc.sql("""
                        UPDATE notification.deliveries SET status = 'DEAD', last_error = :error
                        WHERE id = :id AND status = 'PENDING' AND attempts = :attempt
                        """)
                .param("id", id)
                .param("attempt", attempt)
                .param("error", error)
                .update() == 1;
    }

    /** {@code attempts} counts this one; {@code rideId} may be null. */
    public record Claimed(UUID id, String channel, int attempts, UUID recipientId, String kind, UUID rideId,
            String payload) {
    }
}
