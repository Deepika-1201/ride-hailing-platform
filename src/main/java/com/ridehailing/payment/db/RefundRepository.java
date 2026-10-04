package com.ridehailing.payment.db;

import static com.ridehailing.payment.db.AttemptRepository.instant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Refunds: each against one payment (an attempt), keyed by the refund ID (LLD §4.7, §11.6). */
@Repository
public class RefundRepository {

    private static final String FIELDS = """
            id, charge_id, attempt_id, amount_paise, reason, automatic, status, failure_code, provider_refund_id,
            requested_by, created_at, sent_at, completed_at, checks, version
            """;
    private static final String COLUMNS = "SELECT " + FIELDS + " FROM payment.refunds ";
    private static final String RETURNING = " RETURNING " + FIELDS;

    private final JdbcClient jdbc;

    RefundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public RefundRow insert(UUID id, UUID chargeId, UUID attemptId, long amountPaise, String reason, boolean automatic,
            String requestedBy) {
        return jdbc.sql("""
                        INSERT INTO payment.refunds (id, charge_id, attempt_id, amount_paise, reason, automatic, status,
                            requested_by, created_at)
                        VALUES (:id, :chargeId, :attemptId, :amountPaise, :reason, :automatic, 'PENDING',
                            :requestedBy, now())
                        """ + RETURNING)
                .param("id", id)
                .param("chargeId", chargeId)
                .param("attemptId", attemptId)
                .param("amountPaise", amountPaise)
                .param("reason", reason)
                .param("automatic", automatic)
                .param("requestedBy", requestedBy)
                .query(RefundRepository::refund)
                .single();
    }

    /** Unlocked, to find which charge to lock first. */
    public Optional<UUID> chargeOf(UUID id) {
        return jdbc.sql("SELECT charge_id FROM payment.refunds WHERE id = :id")
                .param("id", id)
                .query(UUID.class)
                .optional();
    }

    public Optional<RefundRow> lock(UUID id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id FOR UPDATE").param("id", id).query(RefundRepository::refund)
                .optional();
    }

    public RefundRow succeed(UUID id, String providerRefundId) {
        return finish(id, "SUCCEEDED", null, providerRefundId);
    }

    public RefundRow fail(UUID id, String failureCode) {
        return finish(id, "FAILED", failureCode, null);
    }

    /** What a send needs: the refunded payment's key and the amount. */
    public Optional<ToSend> toSend(UUID id) {
        return jdbc.sql("""
                        SELECT r.attempt_id, r.amount_paise, c.currency
                        FROM payment.refunds r JOIN payment.charges c ON c.id = r.charge_id
                        WHERE r.id = :id
                        """)
                .param("id", id)
                .query((row, rowNumber) -> new ToSend(row.getObject("attempt_id", UUID.class),
                        row.getLong("amount_paise"), row.getString("currency")))
                .optional();
    }

    public List<RefundRow> ofCharge(UUID chargeId) {
        return jdbc.sql(COLUMNS + "WHERE charge_id = :chargeId ORDER BY created_at, id")
                .param("chargeId", chargeId)
                .query(RefundRepository::refund)
                .list();
    }

    private RefundRow finish(UUID id, String status, String failureCode, String providerRefundId) {
        return jdbc.sql("""
                        UPDATE payment.refunds SET status = :status, failure_code = :failureCode,
                            provider_refund_id = coalesce(:providerRefundId, provider_refund_id),
                            completed_at = now(), lease_until = NULL, next_check_at = NULL, version = version + 1
                        WHERE id = :id
                        """ + RETURNING)
                .param("id", id)
                .param("status", status)
                .param("failureCode", failureCode)
                .param("providerRefundId", providerRefundId)
                .query(RefundRepository::refund)
                .single();
    }

    private static RefundRow refund(ResultSet row, int rowNumber) throws SQLException {
        return new RefundRow(
                row.getObject("id", UUID.class),
                row.getObject("charge_id", UUID.class),
                row.getObject("attempt_id", UUID.class),
                row.getLong("amount_paise"),
                row.getString("reason"),
                row.getBoolean("automatic"),
                row.getString("status"),
                row.getString("failure_code"),
                row.getString("provider_refund_id"),
                row.getString("requested_by"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                instant(row, "sent_at"),
                instant(row, "completed_at"),
                row.getInt("checks"),
                row.getInt("version"));
    }

    public record RefundRow(UUID id, UUID chargeId, UUID attemptId, long amountPaise, String reason, boolean automatic,
            String status, String failureCode, String providerRefundId, String requestedBy, Instant createdAt,
            Instant sentAt, Instant completedAt, int checks, int version) {
    }

    public record ToSend(UUID paymentKey, long amountPaise, String currency) {
    }
}
