package com.ridehailing.dispatch.db;

import com.ridehailing.dispatch.DispatchApi.OfferStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Offers (LLD §4.6): at most one pending per driver and per ride, and one per ride and driver ever (FR-DS3). */
@Repository
public class OfferRepository {

    private static final String RETURNED = """
            id, ride_id, driver_id, attempt, status, rank, distance_m, created_at, expires_at, seen_at, responded_at,
            end_reason, version
            """;
    private static final String COLUMNS = "SELECT " + RETURNED + " FROM dispatch.offers ";

    private final JdbcClient jdbc;

    OfferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A pending offer that expires {@code ttlSeconds} from now by the database clock. */
    public OfferRow insert(UUID id, UUID rideId, UUID driverId, int attempt, int rank, int distanceM, int ttlSeconds) {
        return jdbc.sql("""
                        INSERT INTO dispatch.offers (id, ride_id, driver_id, attempt, status, rank, distance_m,
                                                     created_at, expires_at)
                        VALUES (:id, :rideId, :driverId, :attempt, 'PENDING', :rank, :distanceM, now(),
                                clock_timestamp() + make_interval(secs => :ttl))
                        RETURNING
                        """ + RETURNED)
                .param("id", id)
                .param("rideId", rideId)
                .param("driverId", driverId)
                .param("attempt", attempt)
                .param("rank", rank)
                .param("distanceM", distanceM)
                .param("ttl", ttlSeconds)
                .query(OfferRepository::offer)
                .single();
    }

    /** The ride's offers, oldest first. */
    public List<OfferRow> ofRide(UUID rideId) {
        return jdbc.sql(COLUMNS + "WHERE ride_id = :rideId ORDER BY created_at, id").param("rideId", rideId)
                .query(OfferRepository::offer).list();
    }

    public Optional<OfferRow> find(UUID id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id").param("id", id).query(OfferRepository::offer).optional();
    }

    public Optional<OfferRow> lock(UUID id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id FOR UPDATE").param("id", id).query(OfferRepository::offer)
                .optional();
    }

    public Optional<OfferRow> pendingFor(UUID driverId) {
        return jdbc.sql(COLUMNS + "WHERE driver_id = :driverId AND status = 'PENDING'").param("driverId", driverId)
                .query(OfferRepository::offer).optional();
    }

    public Optional<OfferRow> lockPendingForRide(UUID rideId) {
        return jdbc.sql(COLUMNS + "WHERE ride_id = :rideId AND status = 'PENDING' FOR UPDATE").param("rideId", rideId)
                .query(OfferRepository::offer).optional();
    }

    /** Everyone offered the ride, whatever became of the offer: none is offered it twice (FR-DS3). */
    public Set<UUID> driversOffered(UUID rideId) {
        return new HashSet<>(jdbc.sql("SELECT driver_id FROM dispatch.offers WHERE ride_id = :rideId")
                .param("rideId", rideId).query(UUID.class).list());
    }

    /** Rides with a pending offer to a driver of the city (I6). */
    public List<UUID> ridesWithPendingOffers(String cityId) {
        return jdbc.sql("""
                        SELECT o.ride_id FROM dispatch.offers o
                        JOIN dispatch.driver_availability a ON a.driver_id = o.driver_id
                        WHERE o.status = 'PENDING' AND (CAST(:cityId AS text) IS NULL OR a.city_id = :cityId)
                        """)
                .param("cityId", cityId)
                .query(UUID.class)
                .list();
    }

    /** Offers to drivers of the city created within {@code age}, as offer ID to ride ID (I6). */
    public Map<UUID, UUID> recentOffers(String cityId, Duration age) {
        Map<UUID, UUID> offers = new HashMap<>();
        jdbc.sql("""
                        SELECT o.id, o.ride_id FROM dispatch.offers o
                        JOIN dispatch.driver_availability a ON a.driver_id = o.driver_id
                        WHERE o.created_at > now() - make_interval(secs => :ageS)
                          AND (CAST(:cityId AS text) IS NULL OR a.city_id = :cityId)
                        """)
                .param("ageS", age.toSeconds())
                .param("cityId", cityId)
                .query(row -> {
                    offers.put(row.getObject("id", UUID.class), row.getObject("ride_id", UUID.class));
                });
        return offers;
    }

    /** Only the driver's pending offer, once (§8.5). */
    public void markSeen(UUID id, UUID driverId) {
        jdbc.sql("""
                        UPDATE dispatch.offers SET seen_at = now()
                        WHERE id = :id AND driver_id = :driverId AND status = 'PENDING' AND seen_at IS NULL
                        """)
                .param("id", id)
                .param("driverId", driverId)
                .update();
    }

    /** {@code PENDING → ACCEPTED} if the offer hasn't expired by the database clock (§8.4). */
    public Optional<OfferRow> accept(UUID id, UUID driverId) {
        return jdbc.sql("""
                        UPDATE dispatch.offers SET status = 'ACCEPTED', responded_at = now(), version = version + 1
                        WHERE id = :id AND driver_id = :driverId AND status = 'PENDING'
                          AND expires_at > clock_timestamp()
                        RETURNING
                        """ + RETURNED)
                .param("id", id)
                .param("driverId", driverId)
                .query(OfferRepository::offer)
                .optional();
    }

    /** Ends a pending offer the caller holds locked; {@code responded} when the driver ended it. */
    public OfferRow end(UUID id, OfferStatus to, String reason, boolean responded) {
        return jdbc.sql("""
                        UPDATE dispatch.offers
                        SET status = :to, end_reason = :reason,
                            responded_at = CASE WHEN :responded THEN now() END, version = version + 1
                        WHERE id = :id AND status = 'PENDING'
                        RETURNING
                        """ + RETURNED)
                .param("to", to.name())
                .param("reason", reason)
                .param("responded", responded)
                .param("id", id)
                .query(OfferRepository::offer)
                .single();
    }

    /** Milliseconds left by the database clock, never negative. */
    public long expiresInMs(UUID id) {
        return jdbc.sql("""
                        SELECT greatest(0, floor(extract(epoch FROM expires_at - clock_timestamp()) * 1000))::bigint
                        FROM dispatch.offers WHERE id = :id
                        """)
                .param("id", id)
                .query(Long.class)
                .single();
    }

    /**
     * Pending offers that break I2: two for one driver or one ride, in the city or in all when {@code cityId} is null,
     * and a ride offered twice to one driver.
     */
    public List<String> duplicatedOffers(String cityId) {
        return jdbc.sql("""
                        WITH scoped AS (
                            SELECT o.* FROM dispatch.offers o
                            JOIN dispatch.driver_availability a ON a.driver_id = o.driver_id
                            WHERE CAST(:cityId AS text) IS NULL OR a.city_id = :cityId)
                        SELECT 'driver ' || driver_id || ' has ' || count(*) || ' pending offers' FROM scoped
                        WHERE status = 'PENDING' GROUP BY driver_id HAVING count(*) > 1
                        UNION ALL
                        SELECT 'ride ' || ride_id || ' has ' || count(*) || ' pending offers' FROM scoped
                        WHERE status = 'PENDING' GROUP BY ride_id HAVING count(*) > 1
                        UNION ALL
                        SELECT 'ride ' || ride_id || ' was offered to driver ' || driver_id || ' ' || count(*) || ' times'
                        FROM scoped GROUP BY ride_id, driver_id HAVING count(*) > 1
                        """)
                .param("cityId", cityId)
                .query(String.class)
                .list();
    }

    private static OfferRow offer(ResultSet row, int rowNumber) throws SQLException {
        return new OfferRow(row.getObject("id", UUID.class), row.getObject("ride_id", UUID.class),
                row.getObject("driver_id", UUID.class), row.getInt("attempt"),
                OfferStatus.valueOf(row.getString("status")), row.getInt("rank"), row.getInt("distance_m"),
                instant(row, "created_at"), instant(row, "expires_at"), instant(row, "seen_at"),
                instant(row, "responded_at"), row.getString("end_reason"), row.getInt("version"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record OfferRow(UUID id, UUID rideId, UUID driverId, int attempt, OfferStatus status, int rank,
            int distanceM, Instant createdAt, Instant expiresAt, Instant seenAt, Instant respondedAt,
            String endReason, int version) {
    }
}
