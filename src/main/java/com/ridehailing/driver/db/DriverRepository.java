package com.ridehailing.driver.db;

import com.ridehailing.driver.Verification;
import com.ridehailing.platform.Cursor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Driver rows and their status history (LLD §4.3). */
@Repository
public class DriverRepository {

    private static final String COLUMNS = """
            SELECT id, city_id, first_name, last_name, verification, suspended, suspension_reason, created_at, version
            FROM driver.drivers
            """;

    private final JdbcClient jdbc;

    DriverRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** False if the user is a driver already. */
    public boolean insert(UUID id, String cityId, String firstName, String lastName) {
        return jdbc.sql("""
                        INSERT INTO driver.drivers (id, city_id, first_name, last_name)
                        VALUES (:id, :cityId, :firstName, :lastName)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", id)
                .param("cityId", cityId)
                .param("firstName", firstName)
                .param("lastName", lastName)
                .update() == 1;
    }

    public Optional<DriverRow> find(UUID id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id").param("id", id).query(DriverRepository::driver).optional();
    }

    public Optional<DriverRow> lock(UUID id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id FOR UPDATE").param("id", id).query(DriverRepository::driver)
                .optional();
    }

    /** Blocks changes to the driver, such as a suspension, until the transaction ends. */
    public Optional<DriverRow> lockForShare(UUID id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id FOR SHARE").param("id", id).query(DriverRepository::driver)
                .optional();
    }

    /** Newest first, after the cursor; {@code cityId} and {@code verification} filter when not null. */
    public List<DriverRow> list(String cityId, Verification verification, Cursor after, int limit) {
        return jdbc.sql(COLUMNS + """
                        WHERE (CAST(:cityId AS text) IS NULL OR city_id = :cityId)
                          AND (CAST(:verification AS text) IS NULL OR verification = :verification)
                          AND (CAST(:afterCreatedAt AS timestamptz) IS NULL
                               OR (created_at, id) < (CAST(:afterCreatedAt AS timestamptz), CAST(:afterId AS uuid)))
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("cityId", cityId)
                .param("verification", verification == null ? null : verification.name())
                .param("afterCreatedAt", after == null ? null : after.createdAt().atOffset(ZoneOffset.UTC))
                .param("afterId", after == null ? null : after.id())
                .param("limit", limit)
                .query(DriverRepository::driver)
                .list();
    }

    public DriverRow setVerification(UUID id, Verification verification) {
        return jdbc.sql("""
                        UPDATE driver.drivers SET verification = :verification, updated_at = now(), version = version + 1
                        WHERE id = :id
                        RETURNING id, city_id, first_name, last_name, verification, suspended, suspension_reason,
                                  created_at, version
                        """)
                .param("verification", verification.name())
                .param("id", id)
                .query(DriverRepository::driver)
                .single();
    }

    /** {@code reason} is null when the suspension is lifted. */
    public DriverRow setSuspended(UUID id, boolean suspended, String reason) {
        return jdbc.sql("""
                        UPDATE driver.drivers
                        SET suspended = :suspended, suspension_reason = :reason, updated_at = now(),
                            version = version + 1
                        WHERE id = :id
                        RETURNING id, city_id, first_name, last_name, verification, suspended, suspension_reason,
                                  created_at, version
                        """)
                .param("suspended", suspended)
                .param("reason", reason)
                .param("id", id)
                .query(DriverRepository::driver)
                .single();
    }

    /** {@code cityId} null: every city. */
    public List<UUID> suspended(String cityId) {
        return jdbc.sql("""
                        SELECT id FROM driver.drivers
                        WHERE suspended AND (CAST(:cityId AS text) IS NULL OR city_id = :cityId)
                        """)
                .param("cityId", cityId)
                .query(UUID.class)
                .list();
    }

    public void recordStatusChange(UUID id, UUID driverId, String kind, String from, String to, String reason,
            String actorId, Instant occurredAt) {
        jdbc.sql("""
                        INSERT INTO driver.status_changes (id, driver_id, kind, from_value, to_value, reason, actor_id,
                                                           occurred_at)
                        VALUES (:id, :driverId, :kind, :from, :to, :reason, :actorId, :occurredAt)
                        """)
                .param("id", id)
                .param("driverId", driverId)
                .param("kind", kind)
                .param("from", from)
                .param("to", to)
                .param("reason", reason)
                .param("actorId", actorId)
                .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private static DriverRow driver(ResultSet row, int rowNumber) throws SQLException {
        return new DriverRow(row.getObject("id", UUID.class), row.getString("city_id"), row.getString("first_name"),
                row.getString("last_name"), Verification.valueOf(row.getString("verification")),
                row.getBoolean("suspended"), row.getString("suspension_reason"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(), row.getInt("version"));
    }

    public record DriverRow(UUID id, String cityId, String firstName, String lastName, Verification verification,
            boolean suspended, String suspensionReason, Instant createdAt, int version) {
    }
}
