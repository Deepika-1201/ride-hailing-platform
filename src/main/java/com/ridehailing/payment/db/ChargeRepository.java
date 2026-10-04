package com.ridehailing.payment.db;

import com.ridehailing.platform.Cursor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Charges (LLD §4.7, §11): one per ride and purpose. Locks are taken charge → attempt → refund. */
@Repository
public class ChargeRepository {

    private static final String FIELDS = """
            id, ride_id, rider_id, driver_id, city_id, purpose, amount_paise, commission_paise, currency, method_type,
            payment_method_id, status, failure_code, succeeded_attempt_id, refunded_paise, created_at, updated_at, version
            """;
    private static final String COLUMNS = "SELECT " + FIELDS + " FROM payment.charges ";
    private static final String RETURNING = " RETURNING " + FIELDS;

    private final JdbcClient jdbc;

    ChargeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Empty if the ride already has a charge for the purpose: a redelivered or replayed event (I7). */
    public Optional<ChargeRow> insert(NewCharge charge) {
        return jdbc.sql("""
                        INSERT INTO payment.charges (id, ride_id, rider_id, driver_id, city_id, purpose, amount_paise,
                            commission_paise, currency, method_type, payment_method_id, status, failure_code,
                            created_at, updated_at)
                        VALUES (:id, :rideId, :riderId, :driverId, :cityId, :purpose, :amountPaise, :commissionPaise,
                            :currency, :methodType, :paymentMethodId, :status, :failureCode, now(), now())
                        ON CONFLICT (ride_id, purpose) DO NOTHING
                        """ + RETURNING)
                .param("id", charge.id())
                .param("rideId", charge.rideId())
                .param("riderId", charge.riderId())
                .param("driverId", charge.driverId())
                .param("cityId", charge.cityId())
                .param("purpose", charge.purpose())
                .param("amountPaise", charge.amountPaise())
                .param("commissionPaise", charge.commissionPaise())
                .param("currency", charge.currency())
                .param("methodType", charge.methodType())
                .param("paymentMethodId", charge.paymentMethodId())
                .param("status", charge.status())
                .param("failureCode", charge.failureCode())
                .query(ChargeRepository::charge)
                .optional();
    }

    public Optional<ChargeRow> find(UUID id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id").param("id", id).query(ChargeRepository::charge).optional();
    }

    public Optional<ChargeRow> lock(UUID id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id FOR UPDATE").param("id", id).query(ChargeRepository::charge)
                .optional();
    }

    public ChargeRow succeed(UUID id, UUID attemptId) {
        return jdbc.sql("""
                        UPDATE payment.charges SET status = 'SUCCEEDED', failure_code = NULL,
                            succeeded_attempt_id = :attemptId, updated_at = now(), version = version + 1
                        WHERE id = :id
                        """ + RETURNING)
                .param("id", id)
                .param("attemptId", attemptId)
                .query(ChargeRepository::charge)
                .single();
    }

    public ChargeRow fail(UUID id, String failureCode) {
        return jdbc.sql("""
                        UPDATE payment.charges SET status = 'FAILED', failure_code = :failureCode, updated_at = now(),
                            version = version + 1
                        WHERE id = :id
                        """ + RETURNING)
                .param("id", id)
                .param("failureCode", failureCode)
                .query(ChargeRepository::charge)
                .single();
    }

    /** A {@code PENDING} charge whose attempt has no answer yet. */
    public void markUnknown(UUID id) {
        jdbc.sql("""
                        UPDATE payment.charges SET status = 'UNKNOWN', updated_at = now(), version = version + 1
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", id)
                .update();
    }

    /** Dues being paid with a new attempt: back to {@code PENDING} with the chosen method (LLD §11.7). */
    public ChargeRow pay(UUID id, String methodType, UUID paymentMethodId) {
        return jdbc.sql("""
                        UPDATE payment.charges SET status = 'PENDING', failure_code = NULL, method_type = :methodType,
                            payment_method_id = :paymentMethodId, updated_at = now(), version = version + 1
                        WHERE id = :id
                        """ + RETURNING)
                .param("id", id)
                .param("methodType", methodType)
                .param("paymentMethodId", paymentMethodId)
                .query(ChargeRepository::charge)
                .single();
    }

    /** Reserves an operations refund; false if the charge's non-failed refunds would exceed it (LLD §11.6). */
    public boolean reserveRefund(UUID id, long amountPaise) {
        return jdbc.sql("""
                        UPDATE payment.charges SET refunded_paise = refunded_paise + :amountPaise, updated_at = now(),
                            version = version + 1
                        WHERE id = :id AND refunded_paise + :amountPaise <= amount_paise
                        """)
                .param("id", id)
                .param("amountPaise", amountPaise)
                .update() == 1;
    }

    /** A failed operations refund gives its reservation back. */
    public void releaseRefund(UUID id, long amountPaise) {
        jdbc.sql("""
                        UPDATE payment.charges SET refunded_paise = refunded_paise - :amountPaise, updated_at = now(),
                            version = version + 1
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("amountPaise", amountPaise)
                .update();
    }

    /** The rider's dues, locked in ID order so concurrent payments of them serialize (LLD §11.7). */
    public List<ChargeRow> lockFailed(UUID riderId) {
        return jdbc.sql(COLUMNS + "WHERE rider_id = :riderId AND status = 'FAILED' ORDER BY id FOR UPDATE")
                .param("riderId", riderId)
                .query(ChargeRepository::charge)
                .list();
    }

    /** The rider's dues, oldest first. */
    public List<ChargeRow> failed(UUID riderId) {
        return jdbc.sql(COLUMNS + "WHERE rider_id = :riderId AND status = 'FAILED' ORDER BY created_at, id")
                .param("riderId", riderId)
                .query(ChargeRepository::charge)
                .list();
    }

    /** Newest first after the cursor; {@code status} and {@code unchangedFor} filter when not null. */
    public List<ChargeRow> list(String status, Duration unchangedFor, Cursor after, int limit) {
        return jdbc.sql(COLUMNS + """
                        WHERE (CAST(:status AS text) IS NULL OR status = :status)
                          AND (CAST(:unchangedS AS bigint) IS NULL
                               OR updated_at <= now() - make_interval(secs => :unchangedS))
                          AND (CAST(:afterCreatedAt AS timestamptz) IS NULL
                               OR (created_at, id) < (CAST(:afterCreatedAt AS timestamptz), CAST(:afterId AS uuid)))
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("status", status)
                .param("unchangedS", unchangedFor == null ? null : unchangedFor.toSeconds())
                .param("afterCreatedAt", after == null ? null : after.createdAt().atOffset(ZoneOffset.UTC))
                .param("afterId", after == null ? null : after.id())
                .param("limit", limit)
                .query(ChargeRepository::charge)
                .list();
    }

    /** The database's clock, which every stored time uses. */
    public Instant now() {
        return jdbc.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();
    }

    /**
     * I7 (LLD §17.3, §11.10) over the city's charges, or all with {@code cityId} null: one charge per ride and
     * purpose; reservations equal the non-failed operations refunds; no payment refunded beyond the charge; and no
     * double charge, counting a late success that was refunded automatically as undone.
     */
    public List<String> violations(String cityId) {
        return jdbc.sql("""
                        SELECT 'ride ' || ride_id || ' has ' || count(*) || ' ' || purpose || ' charges'
                        FROM payment.charges
                        WHERE CAST(:cityId AS text) IS NULL OR city_id = :cityId
                        GROUP BY ride_id, purpose HAVING count(*) > 1
                        UNION ALL
                        SELECT 'charge ' || c.id || ' reserves ' || c.refunded_paise || ' paise but its refunds total '
                               || coalesce(sum(r.amount_paise), 0)
                        FROM payment.charges c
                        LEFT JOIN payment.refunds r ON r.charge_id = c.id AND NOT r.automatic AND r.status <> 'FAILED'
                        WHERE CAST(:cityId AS text) IS NULL OR c.city_id = :cityId
                        GROUP BY c.id HAVING c.refunded_paise <> coalesce(sum(r.amount_paise), 0)
                        UNION ALL
                        SELECT 'attempt ' || r.attempt_id || ' has ' || sum(r.amount_paise)
                               || ' paise refunded of a ' || c.amount_paise || ' paise charge'
                        FROM payment.refunds r JOIN payment.charges c ON c.id = r.charge_id
                        WHERE r.status <> 'FAILED' AND (CAST(:cityId AS text) IS NULL OR c.city_id = :cityId)
                        GROUP BY r.attempt_id, c.amount_paise HAVING sum(r.amount_paise) > c.amount_paise
                        UNION ALL
                        SELECT 'charge ' || c.id || ' was paid ' || paid.attempts || ' times with '
                               || coalesce(undone.refunds, 0) || ' automatic refunds'
                        FROM payment.charges c
                        JOIN (SELECT charge_id, count(*) AS attempts FROM payment.charge_attempts
                              WHERE status = 'SUCCEEDED' GROUP BY charge_id) paid ON paid.charge_id = c.id
                        LEFT JOIN (SELECT charge_id, count(*) AS refunds FROM payment.refunds
                                   WHERE automatic AND status <> 'FAILED' GROUP BY charge_id) undone
                               ON undone.charge_id = c.id
                        WHERE (CAST(:cityId AS text) IS NULL OR c.city_id = :cityId)
                          AND paid.attempts - coalesce(undone.refunds, 0) > 1
                        """)
                .param("cityId", cityId)
                .query(String.class)
                .list();
    }

    private static ChargeRow charge(ResultSet row, int rowNumber) throws SQLException {
        return new ChargeRow(
                row.getObject("id", UUID.class),
                row.getObject("ride_id", UUID.class),
                row.getObject("rider_id", UUID.class),
                row.getObject("driver_id", UUID.class),
                row.getString("city_id"),
                row.getString("purpose"),
                row.getLong("amount_paise"),
                row.getLong("commission_paise"),
                row.getString("currency"),
                row.getString("method_type"),
                row.getObject("payment_method_id", UUID.class),
                row.getString("status"),
                row.getString("failure_code"),
                row.getObject("succeeded_attempt_id", UUID.class),
                row.getLong("refunded_paise"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant(),
                row.getInt("version"));
    }

    /** {@code status} is {@code PENDING}, {@code SUCCEEDED} (cash) or {@code FAILED} (no usable method). */
    public record NewCharge(UUID id, UUID rideId, UUID riderId, UUID driverId, String cityId, String purpose,
            long amountPaise, long commissionPaise, String currency, String methodType, UUID paymentMethodId,
            String status, String failureCode) {
    }

    public record ChargeRow(UUID id, UUID rideId, UUID riderId, UUID driverId, String cityId, String purpose,
            long amountPaise, long commissionPaise, String currency, String methodType, UUID paymentMethodId,
            String status, String failureCode, UUID succeededAttemptId, long refundedPaise, Instant createdAt,
            Instant updatedAt, int version) {
    }
}
