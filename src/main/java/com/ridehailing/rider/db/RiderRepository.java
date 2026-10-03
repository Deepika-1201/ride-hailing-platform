package com.ridehailing.rider.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Rider profiles; a rider's row is the lock for changes to their places and methods (LLD §13.3). */
@Repository
public class RiderRepository {

    private static final String COLUMNS = """
            SELECT user_id, first_name, last_name, email, default_payment_method_id, created_at
            FROM rider.riders
            """;

    private final JdbcClient jdbc;

    RiderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** False if the rider exists already. */
    public boolean insertIfAbsent(UUID userId) {
        return jdbc.sql("INSERT INTO rider.riders (user_id) VALUES (:userId) ON CONFLICT (user_id) DO NOTHING")
                .param("userId", userId)
                .update() == 1;
    }

    public Optional<RiderRow> find(UUID userId) {
        return jdbc.sql(COLUMNS + "WHERE user_id = :userId").param("userId", userId).query(RiderRepository::rider)
                .optional();
    }

    public RiderRow lock(UUID userId) {
        return jdbc.sql(COLUMNS + "WHERE user_id = :userId FOR UPDATE").param("userId", userId)
                .query(RiderRepository::rider).single();
    }

    /** Null fields stay as they are. */
    public RiderRow update(UUID userId, String firstName, String lastName, String email) {
        return jdbc.sql("""
                        UPDATE rider.riders
                        SET first_name = coalesce(CAST(:firstName AS text), first_name),
                            last_name = coalesce(CAST(:lastName AS text), last_name),
                            email = coalesce(CAST(:email AS text), email),
                            updated_at = now(), version = version + 1
                        WHERE user_id = :userId
                        RETURNING user_id, first_name, last_name, email, default_payment_method_id, created_at
                        """)
                .param("firstName", firstName)
                .param("lastName", lastName)
                .param("email", email)
                .param("userId", userId)
                .query(RiderRepository::rider)
                .single();
    }

    public void setDefaultMethod(UUID userId, UUID methodId) {
        jdbc.sql("""
                        UPDATE rider.riders SET default_payment_method_id = :methodId, updated_at = now(),
                                                version = version + 1
                        WHERE user_id = :userId
                        """)
                .param("methodId", methodId)
                .param("userId", userId)
                .update();
    }

    private static RiderRow rider(ResultSet row, int rowNumber) throws SQLException {
        return new RiderRow(row.getObject("user_id", UUID.class), row.getString("first_name"),
                row.getString("last_name"), row.getString("email"),
                row.getObject("default_payment_method_id", UUID.class),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    public record RiderRow(UUID id, String firstName, String lastName, String email, UUID defaultPaymentMethodId,
            Instant createdAt) {
    }
}
