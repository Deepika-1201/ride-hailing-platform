package com.ridehailing.payment.db;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Claims of attempts and refunds for the executor and its status checks (LLD §11.2, §11.3, §11.10). Both tables
 * share these columns. A claim locks only the claimed row, with {@code SKIP LOCKED}, so it never waits for a lock.
 */
@Repository
public class ProviderCallRepository {

    private final JdbcClient jdbc;

    ProviderCallRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The oldest {@code PENDING} row becomes {@code IN_FLIGHT}, sent now and leased. */
    public Optional<Claim> claimToSend(Call call, Duration lease) {
        return claim(call, """
                status = 'IN_FLIGHT', sent_at = now(), lease_until = now() + make_interval(secs => :leaseS)
                """, "status = 'PENDING' ORDER BY created_at", lease);
    }

    /** An {@code IN_FLIGHT} row past its lease, its sender presumably dead, becomes {@code UNKNOWN}, checked now. */
    public Optional<Claim> claimExpired(Call call, Duration lease) {
        return claim(call, """
                status = 'UNKNOWN', lease_until = NULL, checks = checks + 1,
                next_check_at = now() + make_interval(secs => :leaseS)
                """, "status = 'IN_FLIGHT' AND lease_until < now() ORDER BY lease_until", lease);
    }

    /** An {@code UNKNOWN} row whose check is due; its next check moves a lease ahead while this one runs. */
    public Optional<Claim> claimDue(Call call, Duration lease) {
        return claim(call, "checks = checks + 1, next_check_at = now() + make_interval(secs => :leaseS)",
                "status = 'UNKNOWN' AND next_check_at <= now() ORDER BY next_check_at", lease);
    }

    /** A send without an answer: {@code IN_FLIGHT} becomes {@code UNKNOWN}, first checked after {@code delay}. */
    public boolean noAnswer(Call call, UUID id, Duration delay) {
        return jdbc.sql("UPDATE " + call.table + """
                         SET status = 'UNKNOWN', lease_until = NULL,
                            next_check_at = now() + make_interval(secs => :delayS), version = version + 1
                        WHERE id = :id AND status = 'IN_FLIGHT'
                        """)
                .param("id", id)
                .param("delayS", seconds(delay))
                .update() == 1;
    }

    /** After an inconclusive check: the next one after {@code delay}, or none when {@code delay} is null. */
    public void nextCheck(Call call, UUID id, Duration delay) {
        jdbc.sql("UPDATE " + call.table + """
                         SET next_check_at = now() + make_interval(secs => CAST(:delayS AS double precision)),
                            version = version + 1
                        WHERE id = :id AND status = 'UNKNOWN'
                        """)
                .param("id", id)
                .param("delayS", delay == null ? null : seconds(delay))
                .update();
    }

    private Optional<Claim> claim(Call call, String assignments, String condition, Duration lease) {
        return jdbc.sql("UPDATE " + call.table + " SET " + assignments + ", version = version + 1 WHERE id = (SELECT id"
                        + " FROM " + call.table + " WHERE " + condition + " LIMIT 1 FOR UPDATE SKIP LOCKED)"
                        + " RETURNING id, charge_id")
                .param("leaseS", seconds(lease))
                .query((row, rowNumber) -> new Claim(row.getObject("id", UUID.class),
                        row.getObject("charge_id", UUID.class)))
                .optional();
    }

    private static double seconds(Duration duration) {
        return duration.toNanos() / 1e9;
    }

    /** The two kinds of provider call. */
    public enum Call {
        ATTEMPT("payment.charge_attempts"),
        REFUND("payment.refunds");

        private final String table;

        Call(String table) {
            this.table = table;
        }
    }

    public record Claim(UUID id, UUID chargeId) {
    }
}
