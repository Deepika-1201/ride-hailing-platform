package com.ridehailing.ride.db;

import com.ridehailing.shared.Ids;
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

/** The operations review queue (LLD §7.11, §13.5). */
@Repository
public class FlagRepository {

    private static final String COLUMNS = """
            SELECT id, ride_id, kind, details::text AS details, created_at, resolved_at, resolution FROM ride.flags
            """;

    private final JdbcClient jdbc;

    FlagRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Opens a flag unless one of the kind is open on the ride. */
    public void open(UUID rideId, FlagKind kind, String detailsJson) {
        jdbc.sql("""
                        INSERT INTO ride.flags (id, ride_id, kind, details, created_at)
                        VALUES (:id, :rideId, :kind, CAST(:details AS jsonb), now())
                        ON CONFLICT (ride_id, kind) WHERE resolved_at IS NULL DO NOTHING
                        """)
                .param("id", Ids.newId())
                .param("rideId", rideId)
                .param("kind", kind.name())
                .param("details", detailsJson)
                .update();
    }

    /** Newest first after the cursor: open or resolved flags, of the kind unless it is null. */
    public List<FlagRow> list(boolean open, String kind, Instant afterCreatedAt, UUID afterId, int limit) {
        return jdbc.sql(COLUMNS + """
                        WHERE (resolved_at IS NULL) = :open
                          AND (CAST(:kind AS text) IS NULL OR kind = :kind)
                          AND (CAST(:afterCreatedAt AS timestamptz) IS NULL
                               OR (created_at, id) < (CAST(:afterCreatedAt AS timestamptz), CAST(:afterId AS uuid)))
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("open", open)
                .param("kind", kind)
                .param("afterCreatedAt", afterCreatedAt == null ? null : afterCreatedAt.atOffset(ZoneOffset.UTC))
                .param("afterId", afterId)
                .param("limit", limit)
                .query(FlagRepository::flag)
                .list();
    }

    /** Oldest first. */
    public List<FlagRow> ofRide(UUID rideId) {
        return jdbc.sql(COLUMNS + "WHERE ride_id = :rideId ORDER BY created_at, id").param("rideId", rideId)
                .query(FlagRepository::flag).list();
    }

    public Optional<FlagRow> find(UUID id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id").param("id", id).query(FlagRepository::flag).optional();
    }

    /** Empty if the flag is resolved already. */
    public Optional<FlagRow> resolve(UUID id, UUID resolvedBy, String resolution) {
        return jdbc.sql("""
                        UPDATE ride.flags SET resolved_at = now(), resolved_by = :resolvedBy, resolution = :resolution
                        WHERE id = :id AND resolved_at IS NULL
                        RETURNING id, ride_id, kind, details::text AS details, created_at, resolved_at, resolution
                        """)
                .param("id", id)
                .param("resolvedBy", resolvedBy)
                .param("resolution", resolution)
                .query(FlagRepository::flag)
                .optional();
    }

    private static FlagRow flag(ResultSet row, int rowNumber) throws SQLException {
        OffsetDateTime resolvedAt = row.getObject("resolved_at", OffsetDateTime.class);
        return new FlagRow(row.getObject("id", UUID.class), row.getObject("ride_id", UUID.class),
                row.getString("kind"), row.getString("details"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                resolvedAt == null ? null : resolvedAt.toInstant(), row.getString("resolution"));
    }

    /** {@code details} is JSON text; {@code resolvedAt} and {@code resolution} are null while open. */
    public record FlagRow(UUID id, UUID rideId, String kind, String details, Instant createdAt, Instant resolvedAt,
            String resolution) {
    }

    public enum FlagKind {
        ARRIVED_FAR,
        DRIVER_CANCELLED_AT_PICKUP,
        PIN_LOCKED,
        STUCK
    }
}
