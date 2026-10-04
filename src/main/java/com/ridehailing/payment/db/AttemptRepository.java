package com.ridehailing.payment.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Charge attempts: one provider call each, keyed by the attempt ID; at most one open per charge (LLD §4.7). */
@Repository
public class AttemptRepository {

    private static final String FIELDS = """
            id, charge_id, seq, status, provider, payment_method_id, method_ref, provider_payment_id, failure_code,
            created_at, sent_at, completed_at, next_check_at, checks
            """;
    private static final String COLUMNS = "SELECT " + FIELDS + " FROM payment.charge_attempts ";
    private static final String RETURNING = " RETURNING " + FIELDS;

    private final JdbcClient jdbc;

    AttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A {@code PENDING} attempt numbered after the charge's last. */
    public AttemptRow insert(UUID id, UUID chargeId, String provider, UUID paymentMethodId, String methodRef) {
        return jdbc.sql("""
                        INSERT INTO payment.charge_attempts (id, charge_id, seq, status, provider, payment_method_id,
                            method_ref, created_at)
                        SELECT :id, :chargeId, coalesce(max(seq), 0) + 1, 'PENDING', :provider, :paymentMethodId,
                            :methodRef, now()
                        FROM payment.charge_attempts WHERE charge_id = :chargeId
                        """ + RETURNING)
                .param("id", id)
                .param("chargeId", chargeId)
                .param("provider", provider)
                .param("paymentMethodId", paymentMethodId)
                .param("methodRef", methodRef)
                .query(AttemptRepository::attempt)
                .single();
    }

    /** Unlocked, to find which charge to lock first. */
    public Optional<UUID> chargeOf(UUID id) {
        return jdbc.sql("SELECT charge_id FROM payment.charge_attempts WHERE id = :id")
                .param("id", id)
                .query(UUID.class)
                .optional();
    }

    public Optional<AttemptRow> lock(UUID id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id FOR UPDATE").param("id", id).query(AttemptRepository::attempt)
                .optional();
    }

    public AttemptRow succeed(UUID id, String providerPaymentId) {
        return finish(id, "SUCCEEDED", null, providerPaymentId);
    }

    public AttemptRow fail(UUID id, String failureCode) {
        return finish(id, "FAILED", failureCode, null);
    }

    /** Closes the charge's attempt not yet sent, once another attempt has paid the charge (LLD §11.10). */
    public int supersede(UUID chargeId) {
        return jdbc.sql("""
                        UPDATE payment.charge_attempts SET status = 'FAILED', failure_code = 'SUPERSEDED',
                            completed_at = now(), version = version + 1
                        WHERE charge_id = :chargeId AND status = 'PENDING'
                        """)
                .param("chargeId", chargeId)
                .update();
    }

    /** What a send needs: the method's token and the charge's amount. */
    public Optional<ToSend> toSend(UUID id) {
        return jdbc.sql("""
                        SELECT a.method_ref, c.amount_paise, c.currency
                        FROM payment.charge_attempts a JOIN payment.charges c ON c.id = a.charge_id
                        WHERE a.id = :id
                        """)
                .param("id", id)
                .query((row, rowNumber) -> new ToSend(row.getString("method_ref"), row.getLong("amount_paise"),
                        row.getString("currency")))
                .optional();
    }

    /** The charges' attempts, in order. */
    public List<AttemptRow> ofCharges(Collection<UUID> chargeIds) {
        if (chargeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(COLUMNS + "WHERE charge_id IN (:chargeIds) ORDER BY charge_id, seq")
                .param("chargeIds", chargeIds)
                .query(AttemptRepository::attempt)
                .list();
    }

    private AttemptRow finish(UUID id, String status, String failureCode, String providerPaymentId) {
        return jdbc.sql("""
                        UPDATE payment.charge_attempts SET status = :status, failure_code = :failureCode,
                            provider_payment_id = coalesce(:providerPaymentId, provider_payment_id),
                            completed_at = now(), lease_until = NULL, next_check_at = NULL, version = version + 1
                        WHERE id = :id
                        """ + RETURNING)
                .param("id", id)
                .param("status", status)
                .param("failureCode", failureCode)
                .param("providerPaymentId", providerPaymentId)
                .query(AttemptRepository::attempt)
                .single();
    }

    private static AttemptRow attempt(ResultSet row, int rowNumber) throws SQLException {
        return new AttemptRow(
                row.getObject("id", UUID.class),
                row.getObject("charge_id", UUID.class),
                row.getInt("seq"),
                row.getString("status"),
                row.getString("provider"),
                row.getObject("payment_method_id", UUID.class),
                row.getString("method_ref"),
                row.getString("provider_payment_id"),
                row.getString("failure_code"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                instant(row, "sent_at"),
                instant(row, "completed_at"),
                instant(row, "next_check_at"),
                row.getInt("checks"));
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record AttemptRow(UUID id, UUID chargeId, int seq, String status, String provider, UUID paymentMethodId,
            String methodRef, String providerPaymentId, String failureCode, Instant createdAt, Instant sentAt,
            Instant completedAt, Instant nextCheckAt, int checks) {
    }

    public record ToSend(String methodRef, long amountPaise, String currency) {
    }
}
