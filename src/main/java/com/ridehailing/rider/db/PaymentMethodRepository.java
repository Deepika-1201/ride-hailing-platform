package com.ridehailing.rider.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Payment methods: one cash method per rider, plus mock card and UPI tokens (LLD §4.3). */
@Repository
public class PaymentMethodRepository {

    private static final String COLUMNS = "SELECT id, type, display, active, created_at FROM rider.payment_methods ";

    private final JdbcClient jdbc;

    PaymentMethodRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertCash(UUID id, UUID riderId) {
        jdbc.sql("INSERT INTO rider.payment_methods (id, rider_id, type, display) VALUES (:id, :riderId, 'CASH', 'Cash')")
                .param("id", id)
                .param("riderId", riderId)
                .update();
    }

    public void insert(UUID id, UUID riderId, String type, String providerRef, String display) {
        jdbc.sql("""
                        INSERT INTO rider.payment_methods (id, rider_id, type, provider_ref, display)
                        VALUES (:id, :riderId, :type, :providerRef, :display)
                        """)
                .param("id", id)
                .param("riderId", riderId)
                .param("type", type)
                .param("providerRef", providerRef)
                .param("display", display)
                .update();
    }

    /** Active methods, oldest first, so cash comes first. */
    public List<MethodRow> active(UUID riderId) {
        return jdbc.sql(COLUMNS + "WHERE rider_id = :riderId AND active ORDER BY created_at, id")
                .param("riderId", riderId)
                .query(PaymentMethodRepository::method)
                .list();
    }

    /** The rider's active method; empty if it's someone else's or was removed. */
    public Optional<MethodRow> activeOf(UUID riderId, UUID id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id AND rider_id = :riderId AND active")
                .param("id", id)
                .param("riderId", riderId)
                .query(PaymentMethodRepository::method)
                .optional();
    }

    public UUID cashOf(UUID riderId) {
        return jdbc.sql("SELECT id FROM rider.payment_methods WHERE rider_id = :riderId AND type = 'CASH'")
                .param("riderId", riderId)
                .query(UUID.class)
                .single();
    }

    public void deactivate(UUID id) {
        jdbc.sql("UPDATE rider.payment_methods SET active = false WHERE id = :id").param("id", id).update();
    }

    private static MethodRow method(ResultSet row, int rowNumber) throws SQLException {
        return new MethodRow(row.getObject("id", UUID.class), row.getString("type"), row.getString("display"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    public record MethodRow(UUID id, String type, String display, Instant createdAt) {
    }
}
