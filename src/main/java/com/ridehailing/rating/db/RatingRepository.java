package com.ridehailing.rating.db;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

/** Rating windows, ratings and summaries (LLD §4.7, §13.4). */
@Repository
public class RatingRepository {

    private final JdbcClient jdbc;

    RatingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Does nothing if the ride's window exists: a redelivered {@code TripCompleted}. */
    public void openWindow(UUID rideId, UUID riderId, UUID driverId, Instant completedAt, Duration open) {
        jdbc.sql("""
                        INSERT INTO rating.rating_windows (ride_id, rider_id, driver_id, completed_at, closes_at)
                        VALUES (:rideId, :riderId, :driverId, :completedAt,
                            :completedAt + make_interval(secs => :openS))
                        ON CONFLICT (ride_id) DO NOTHING
                        """)
                .param("rideId", rideId)
                .param("riderId", riderId)
                .param("driverId", driverId)
                .param("completedAt", completedAt.atOffset(ZoneOffset.UTC))
                .param("openS", open.toSeconds())
                .update();
    }

    /** The ride's window while it is open, by the database's clock. */
    public Optional<WindowRow> openWindow(UUID rideId) {
        return jdbc.sql("""
                        SELECT ride_id, rider_id, driver_id FROM rating.rating_windows
                        WHERE ride_id = :rideId AND closes_at > now()
                        """)
                .param("rideId", rideId)
                .query((row, rowNumber) -> new WindowRow(row.getObject("ride_id", UUID.class),
                        row.getObject("rider_id", UUID.class), row.getObject("driver_id", UUID.class)))
                .optional();
    }

    public boolean rated(UUID rideId, String raterRole) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM rating.ratings WHERE ride_id = :rideId AND rater_role = :role)")
                .param("rideId", rideId)
                .param("role", raterRole)
                .query(Boolean.class)
                .single();
    }

    public RatingRow insert(UUID id, UUID rideId, String raterRole, UUID raterId, UUID rateeId, int stars,
            String comment) {
        return jdbc.sql("""
                        INSERT INTO rating.ratings (id, ride_id, rater_role, rater_id, ratee_id, stars, comment,
                            created_at)
                        VALUES (:id, :rideId, :raterRole, :raterId, :rateeId, :stars, :comment, now())
                        RETURNING id, ride_id, rater_role, rater_id, ratee_id, stars, comment, created_at
                        """)
                .param("id", id)
                .param("rideId", rideId)
                .param("raterRole", raterRole)
                .param("raterId", raterId)
                .param("rateeId", rateeId)
                .param("stars", stars)
                .param("comment", comment)
                .query(RatingRepository::rating)
                .single();
    }

    /** Creates the person's summary as this party if missing and locks it, so their ratings queue. */
    public void lockSummary(UUID userId, String party) {
        jdbc.sql("""
                        INSERT INTO rating.summaries (user_id, party, average, count, updated_at)
                        VALUES (:userId, :party, 0, 0, now())
                        ON CONFLICT (user_id, party) DO NOTHING
                        """)
                .param("userId", userId)
                .param("party", party)
                .update();
        jdbc.sql("SELECT 1 FROM rating.summaries WHERE user_id = :userId AND party = :party FOR UPDATE")
                .param("userId", userId)
                .param("party", party)
                .query(Integer.class)
                .single();
    }

    /** The average of the person's latest 100 ratings as this party, rated by the other party (FR-RT2). */
    public void refreshSummary(UUID userId, String party, String raterRole) {
        jdbc.sql("""
                        UPDATE rating.summaries s SET average = latest.average, count = latest.count, updated_at = now()
                        FROM (SELECT round(avg(stars), 2) AS average, count(*) AS count
                              FROM (SELECT stars FROM rating.ratings
                                    WHERE ratee_id = :userId AND rater_role = :raterRole
                                    ORDER BY created_at DESC, id DESC LIMIT 100) recent) latest
                        WHERE s.user_id = :userId AND s.party = :party
                        """)
                .param("userId", userId)
                .param("party", party)
                .param("raterRole", raterRole)
                .update();
    }

    public Optional<SummaryRow> summary(UUID userId, String party) {
        return jdbc.sql("SELECT average, count FROM rating.summaries WHERE user_id = :userId AND party = :party")
                .param("userId", userId)
                .param("party", party)
                .query((row, rowNumber) -> new SummaryRow(row.getBigDecimal("average"), row.getInt("count")))
                .optional();
    }

    public Map<UUID, SummaryRow> summaries(Collection<UUID> userIds, String party) {
        Map<UUID, SummaryRow> found = new HashMap<>();
        jdbc.sql("SELECT user_id, average, count FROM rating.summaries WHERE party = :party AND user_id = ANY(:ids)")
                .param("party", party)
                .param("ids", new SqlArrayValue("uuid", userIds.toArray()))
                .query(row -> {
                    found.put(row.getObject("user_id", UUID.class),
                            new SummaryRow(row.getBigDecimal("average"), row.getInt("count")));
                });
        return found;
    }

    private static RatingRow rating(ResultSet row, int rowNumber) throws SQLException {
        return new RatingRow(row.getObject("id", UUID.class), row.getObject("ride_id", UUID.class),
                row.getString("rater_role"), row.getObject("rater_id", UUID.class),
                row.getObject("ratee_id", UUID.class), row.getInt("stars"), row.getString("comment"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    public record WindowRow(UUID rideId, UUID riderId, UUID driverId) {
    }

    public record RatingRow(UUID id, UUID rideId, String raterRole, UUID raterId, UUID rateeId, int stars,
            String comment, Instant createdAt) {
    }

    public record SummaryRow(BigDecimal average, int count) {
    }
}
