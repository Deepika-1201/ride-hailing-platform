package com.ridehailing.identity.db;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** One-time-code challenges; their expiry is judged by the database clock (LLD §1.4, §12.1). */
@Repository
public class OtpChallenges {

    private final JdbcClient jdbc;

    OtpChallenges(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns when the challenge expires. */
    public Instant create(UUID id, String phone, byte[] codeHmac, Duration ttl, String requestIp) {
        return jdbc.sql("""
                        INSERT INTO identity.otp_challenges (id, phone, code_hmac, expires_at, request_ip)
                        VALUES (:id, :phone, :codeHmac, now() + make_interval(secs => :ttl), CAST(:ip AS inet))
                        RETURNING expires_at
                        """)
                .param("id", id)
                .param("phone", phone)
                .param("codeHmac", codeHmac)
                .param("ttl", (double) ttl.toSeconds())
                .param("ip", requestIp)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    /** The phone's newest challenge that can still be answered, locked so concurrent guesses are counted in turn. */
    public Optional<Challenge> latestOpen(String phone, int maxAttempts) {
        return jdbc.sql("""
                        SELECT id, code_hmac FROM identity.otp_challenges
                        WHERE phone = :phone AND consumed_at IS NULL AND expires_at > now() AND attempts < :maxAttempts
                        ORDER BY created_at DESC, id DESC LIMIT 1
                        FOR UPDATE
                        """)
                .param("phone", phone)
                .param("maxAttempts", maxAttempts)
                .query((row, rowNumber) -> new Challenge(row.getObject("id", UUID.class), row.getBytes("code_hmac")))
                .optional();
    }

    /** Returns the attempts so far. */
    public int recordWrongCode(UUID id) {
        return jdbc.sql("UPDATE identity.otp_challenges SET attempts = attempts + 1 WHERE id = :id RETURNING attempts")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    public void consume(UUID id) {
        jdbc.sql("UPDATE identity.otp_challenges SET consumed_at = now() WHERE id = :id").param("id", id).update();
    }

    /** Deletes up to {@code batch} challenges created before {@code age} ago; returns how many. */
    public int deleteOlderThan(Duration age, int batch) {
        return jdbc.sql("""
                        DELETE FROM identity.otp_challenges WHERE id IN (
                            SELECT id FROM identity.otp_challenges
                            WHERE created_at < now() - make_interval(secs => :age) LIMIT :batch)
                        """)
                .param("age", (double) age.toSeconds())
                .param("batch", batch)
                .update();
    }

    public record Challenge(UUID id, byte[] codeHmac) {
    }
}
