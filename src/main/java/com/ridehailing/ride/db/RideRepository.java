package com.ridehailing.ride.db;

import com.ridehailing.ride.RideQueries.DrivenRide;
import com.ridehailing.ride.RideStatus;
import com.ridehailing.shared.GeoPoint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

/** Rides (LLD §4.5). Every change adds 1 to the version and is guarded by the expected one (ADR-010). */
@Repository
public class RideRepository {

    private static final String RETURNED = """
            id, rider_id, city_id, category, quote_id, pickup_lat, pickup_lon, dropoff_lat, dropoff_lon, pickup_zone,
            fare_paise, currency, payment_method_id, payment_method_type, status, version, search_generation,
            driver_id, vehicle_id, offer_id, rider_snapshot::text AS rider_snapshot,
            driver_snapshot::text AS driver_snapshot, pin, promised_pickup_eta_s, requested_at, assigned_at,
            arrived_at, started_at, completed_at, ended_at, cancelled_by, cancel_reason, reassign_count,
            commission_paise, fee_rule_id, pin_attempts, fee_purpose, fee_paise, distance_m, duration_s,
            fare_breakdown::text AS fare_breakdown
            """;
    private static final String COLUMNS = "SELECT " + RETURNED + " FROM ride.rides ";
    private static final List<String> ACTIVE = List.of("SEARCHING", "DRIVER_ASSIGNED", "DRIVER_ARRIVED", "IN_TRIP");
    /** Active rides past their state's threshold, timed from their latest transition (ride lifecycle §9). */
    private static final String OVERDUE = """
            SELECT r.id, r.status, t.occurred_at FROM ride.rides r
            JOIN LATERAL (SELECT occurred_at FROM ride.transitions
                          WHERE ride_id = r.id ORDER BY version DESC LIMIT 1) t ON true
            WHERE r.status IN (:active)
              AND t.occurred_at < now() - make_interval(secs => CASE r.status
                    WHEN 'SEARCHING' THEN :searching WHEN 'DRIVER_ASSIGNED' THEN :assigned
                    WHEN 'DRIVER_ARRIVED' THEN :arrived ELSE :inTrip END)
            """;

    private final JdbcClient jdbc;

    RideRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A ride in {@code SEARCHING}, requested now. */
    public RideRow insert(NewRide ride) {
        return jdbc.sql("""
                        INSERT INTO ride.rides (id, rider_id, city_id, category, quote_id, pickup_lat, pickup_lon,
                                                dropoff_lat, dropoff_lon, pickup_zone, distance_m, duration_s,
                                                fare_paise, commission_paise, fare_breakdown, currency, fee_rule_id,
                                                payment_method_id, payment_method_type, status, rider_snapshot,
                                                requested_at)
                        VALUES (:id, :riderId, :cityId, :category, :quoteId, :pickupLat, :pickupLon, :dropoffLat,
                                :dropoffLon, :pickupZone, :distanceM, :durationS, :farePaise, :commissionPaise,
                                CAST(:fareBreakdown AS jsonb), :currency, :feeRuleId, :paymentMethodId,
                                :paymentMethodType, 'SEARCHING', CAST(:riderSnapshot AS jsonb), now())
                        RETURNING
                        """ + RETURNED)
                .param("id", ride.id())
                .param("riderId", ride.riderId())
                .param("cityId", ride.cityId())
                .param("category", ride.category())
                .param("quoteId", ride.quoteId())
                .param("pickupLat", ride.pickup().lat())
                .param("pickupLon", ride.pickup().lon())
                .param("dropoffLat", ride.dropoff().lat())
                .param("dropoffLon", ride.dropoff().lon())
                .param("pickupZone", ride.pickupZone())
                .param("distanceM", ride.distanceM())
                .param("durationS", ride.durationS())
                .param("farePaise", ride.farePaise())
                .param("commissionPaise", ride.commissionPaise())
                .param("fareBreakdown", ride.fareBreakdown())
                .param("currency", ride.currency())
                .param("feeRuleId", ride.feeRuleId())
                .param("paymentMethodId", ride.paymentMethodId())
                .param("paymentMethodType", ride.paymentMethodType())
                .param("riderSnapshot", ride.riderSnapshot())
                .query(RideRepository::ride)
                .single();
    }

    public Optional<RideRow> find(UUID id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id").param("id", id).query(RideRepository::ride).optional();
    }

    public Optional<RideRow> lock(UUID id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id FOR UPDATE").param("id", id).query(RideRepository::ride)
                .optional();
    }

    /** Held until the transaction ends: a writer of the ride waits for it, and it for them (§6.4). */
    public Optional<RideRow> lockForShare(UUID id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id FOR SHARE").param("id", id).query(RideRepository::ride)
                .optional();
    }

    public boolean hasActiveRide(UUID riderId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM ride.rides WHERE rider_id = :riderId AND status IN (:active))")
                .param("riderId", riderId)
                .param("active", ACTIVE)
                .query(Boolean.class)
                .single();
    }

    /** {@code SEARCHING → DRIVER_ASSIGNED}; empty if the ride moved on from the expected version. */
    public Optional<RideRow> assign(UUID id, int version, UUID driverId, UUID vehicleId, UUID offerId, String pin,
            int promisedPickupEtaS, String driverSnapshot) {
        return jdbc.sql("""
                        UPDATE ride.rides
                        SET status = 'DRIVER_ASSIGNED', driver_id = :driverId, vehicle_id = :vehicleId,
                            offer_id = :offerId, pin = :pin, promised_pickup_eta_s = :eta,
                            driver_snapshot = CAST(:driverSnapshot AS jsonb), assigned_at = now(),
                            version = version + 1
                        WHERE id = :id AND status = 'SEARCHING' AND version = :version
                        RETURNING
                        """ + RETURNED)
                .param("driverId", driverId)
                .param("vehicleId", vehicleId)
                .param("offerId", offerId)
                .param("pin", pin)
                .param("eta", promisedPickupEtaS)
                .param("driverSnapshot", driverSnapshot)
                .param("id", id)
                .param("version", version)
                .query(RideRepository::ride)
                .optional();
    }

    /** {@code SEARCHING} to an end state; {@code cancelledBy} and {@code reason} are null unless cancelled. */
    public Optional<RideRow> endSearch(UUID id, int version, RideStatus to, String cancelledBy, String reason) {
        return end(id, version, RideStatus.SEARCHING, to, cancelledBy, reason, null, null);
    }

    /**
     * From {@code from} to an end state (T3, T4, T8, T10, T11, T13). A driver stays on the ride for the record;
     * {@code cancelledBy}, {@code reason} and the fee are null when they don't apply.
     */
    public Optional<RideRow> end(UUID id, int version, RideStatus from, RideStatus to, String cancelledBy,
            String reason, String feePurpose, Long feePaise) {
        return jdbc.sql("""
                        UPDATE ride.rides
                        SET status = :to, cancelled_by = :cancelledBy, cancel_reason = :reason,
                            fee_purpose = :feePurpose, fee_paise = :feePaise, ended_at = now(), version = version + 1
                        WHERE id = :id AND status = :from AND version = :version
                        RETURNING
                        """ + RETURNED)
                .param("to", to.name())
                .param("cancelledBy", cancelledBy)
                .param("reason", reason)
                .param("feePurpose", feePurpose)
                .param("feePaise", feePaise)
                .param("id", id)
                .param("from", from.name())
                .param("version", version)
                .query(RideRepository::ride)
                .optional();
    }

    /** A transition that stamps its time: to {@code DRIVER_ARRIVED}, {@code IN_TRIP} or {@code COMPLETED}. */
    public Optional<RideRow> advance(UUID id, int version, RideStatus from, RideStatus to) {
        String stamp = switch (to) {
            case DRIVER_ARRIVED -> "arrived_at = now()";
            case IN_TRIP -> "started_at = now()";
            case COMPLETED -> "completed_at = now(), ended_at = now()";
            default -> throw new IllegalArgumentException("No time to stamp for " + to);
        };
        return jdbc.sql("""
                        UPDATE ride.rides SET status = :to, %s, version = version + 1
                        WHERE id = :id AND status = :from AND version = :version
                        RETURNING
                        """.formatted(stamp) + RETURNED)
                .param("to", to.name())
                .param("id", id)
                .param("from", from.name())
                .param("version", version)
                .query(RideRepository::ride)
                .optional();
    }

    /**
     * {@code DRIVER_ASSIGNED → SEARCHING} (T6, T7): the driver's fields cleared, and a new search generation. PIN
     * attempts need no reset: they start at arrival, which can't be undone.
     */
    public Optional<RideRow> unassign(UUID id, int version) {
        return jdbc.sql("""
                        UPDATE ride.rides
                        SET status = 'SEARCHING', driver_id = NULL, vehicle_id = NULL, offer_id = NULL, pin = NULL,
                            promised_pickup_eta_s = NULL, driver_snapshot = NULL,
                            assigned_at = NULL, search_generation = search_generation + 1,
                            reassign_count = reassign_count + 1, version = version + 1
                        WHERE id = :id AND status = 'DRIVER_ASSIGNED' AND version = :version
                        RETURNING
                        """ + RETURNED)
                .param("id", id)
                .param("version", version)
                .query(RideRepository::ride)
                .optional();
    }

    /** A wrong PIN on a ride the caller holds locked; not a transition, so the version stays (LLD §7.1). */
    public RideRow countWrongPin(UUID id) {
        return jdbc.sql("UPDATE ride.rides SET pin_attempts = pin_attempts + 1 WHERE id = :id RETURNING " + RETURNED)
                .param("id", id)
                .query(RideRepository::ride)
                .single();
    }

    /** The database's time for the current transaction, which stamps every change in it. */
    public Instant now() {
        return jdbc.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();
    }

    /** Rides with a driver now (I4), in the city or in all when {@code cityId} is null. */
    public List<DrivenRide> drivenRides(String cityId) {
        return jdbc.sql("""
                        SELECT driver_id, id, status FROM ride.rides
                        WHERE status IN (:driven) AND (CAST(:cityId AS text) IS NULL OR city_id = :cityId)
                        """)
                .param("driven", ACTIVE.subList(1, ACTIVE.size()))
                .param("cityId", cityId)
                .query((row, number) -> new DrivenRide(row.getObject("driver_id", UUID.class),
                        row.getObject("id", UUID.class), RideStatus.valueOf(row.getString("status"))))
                .list();
    }

    /**
     * Operations' list (LLD §13.5): newest first after the cursor; no statuses means any, a null city every city. Only
     * the filters given are in the query, so the planner can use {@code rides_by_status} or {@code rides_newest}.
     */
    public List<RideRow> list(Collection<RideStatus> statuses, String cityId, Instant afterRequestedAt, UUID afterId,
            int limit) {
        StringBuilder where = new StringBuilder("WHERE true");
        if (!statuses.isEmpty()) {
            where.append(" AND status = ANY(:statuses)");
        }
        if (cityId != null) {
            where.append(" AND city_id = :cityId");
        }
        if (afterRequestedAt != null) {
            where.append(" AND (requested_at, id) < (:afterRequestedAt, :afterId)");
        }
        JdbcClient.StatementSpec query = jdbc.sql(COLUMNS + where + " ORDER BY requested_at DESC, id DESC LIMIT :limit")
                .param("limit", limit);
        if (!statuses.isEmpty()) {
            query = query.param("statuses", new SqlArrayValue("text", statuses.stream().map(RideStatus::name).toArray()));
        }
        if (cityId != null) {
            query = query.param("cityId", cityId);
        }
        if (afterRequestedAt != null) {
            query = query.param("afterRequestedAt", afterRequestedAt.atOffset(ZoneOffset.UTC)).param("afterId", afterId);
        }
        return query.query(RideRepository::ride).list();
    }

    /** The rider's rides, newest first after the cursor (LLD §13.6), by {@code rides_rider_history}. */
    public List<RideRow> ofRider(UUID riderId, Instant afterRequestedAt, UUID afterId, int limit) {
        return history("rider_id", riderId, afterRequestedAt, afterId, limit);
    }

    /** The rides the driver drives or ended with, newest first after the cursor, by {@code rides_driver_history}. */
    public List<RideRow> ofDriver(UUID driverId, Instant afterRequestedAt, UUID afterId, int limit) {
        return history("driver_id", driverId, afterRequestedAt, afterId, limit);
    }

    /** By {@code one_active_ride_per_rider}, whose predicate this repeats. */
    public Optional<RideRow> activeOfRider(UUID riderId) {
        return jdbc.sql(COLUMNS + """
                        WHERE rider_id = :riderId
                          AND status IN ('SEARCHING', 'DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_TRIP')
                        """)
                .param("riderId", riderId)
                .query(RideRepository::ride)
                .optional();
    }

    /** By {@code one_active_ride_per_driver}, whose predicate this repeats. */
    public Optional<RideRow> activeOfDriver(UUID driverId) {
        return jdbc.sql(COLUMNS + """
                        WHERE driver_id = :driverId AND status IN ('DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_TRIP')
                        """)
                .param("driverId", driverId)
                .query(RideRepository::ride)
                .optional();
    }

    /** Only with a cursor is its bound in the query, so the first page reads the index from its start. */
    private List<RideRow> history(String party, UUID userId, Instant afterRequestedAt, UUID afterId, int limit) {
        String after = afterRequestedAt == null ? "" : " AND (requested_at, id) < (:afterRequestedAt, :afterId)";
        JdbcClient.StatementSpec query = jdbc.sql(COLUMNS + "WHERE " + party + " = :userId" + after
                        + " ORDER BY requested_at DESC, id DESC LIMIT :limit")
                .param("userId", userId)
                .param("limit", limit);
        if (afterRequestedAt != null) {
            query = query.param("afterRequestedAt", afterRequestedAt.atOffset(ZoneOffset.UTC)).param("afterId", afterId);
        }
        return query.query(RideRepository::ride).list();
    }

    public Map<UUID, RideStatus> statuses(Collection<UUID> ids) {
        Map<UUID, RideStatus> statuses = new HashMap<>();
        jdbc.sql("SELECT id, status FROM ride.rides WHERE id = ANY(:ids)")
                .param("ids", new SqlArrayValue("uuid", ids.toArray()))
                .query(row -> {
                    statuses.put(row.getObject("id", UUID.class), RideStatus.valueOf(row.getString("status")));
                });
        return statuses;
    }

    /** Rides of the city (or all, for null) that ended within {@code age}. */
    public List<UUID> endedWithin(String cityId, Duration age) {
        return jdbc.sql("""
                        SELECT id FROM ride.rides
                        WHERE ended_at > now() - make_interval(secs => :ageS)
                          AND (CAST(:cityId AS text) IS NULL OR city_id = :cityId)
                        """)
                .param("ageS", age.toSeconds())
                .param("cityId", cityId)
                .query(UUID.class)
                .list();
    }

    /**
     * Active rides in their state for longer than its threshold, counted from their latest transition, that have no
     * open {@code STUCK} flag; oldest first.
     */
    public List<OverdueRide> overdueUnflagged(Map<RideStatus, Duration> thresholds, int limit) {
        return jdbc.sql(OVERDUE + """
                          AND NOT EXISTS (SELECT 1 FROM ride.flags f
                                          WHERE f.ride_id = r.id AND f.kind = 'STUCK' AND f.resolved_at IS NULL)
                        ORDER BY t.occurred_at, r.id
                        LIMIT :limit
                        """)
                .params(overdueParams(thresholds))
                .param("limit", limit)
                .query((row, number) -> new OverdueRide(row.getObject("id", UUID.class),
                        RideStatus.valueOf(row.getString("status")),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** How many active rides are past their state's threshold, flagged or not, by state. */
    public Map<RideStatus, Integer> overdueCounts(Map<RideStatus, Duration> thresholds) {
        Map<RideStatus, Integer> counts = new HashMap<>();
        jdbc.sql("SELECT status, count(*) AS rides FROM (" + OVERDUE + ") overdue GROUP BY status")
                .params(overdueParams(thresholds))
                .query(row -> {
                    counts.put(RideStatus.valueOf(row.getString("status")), row.getInt("rides"));
                });
        return counts;
    }

    private static Map<String, Object> overdueParams(Map<RideStatus, Duration> thresholds) {
        return Map.of("active", ACTIVE,
                "searching", thresholds.get(RideStatus.SEARCHING).toSeconds(),
                "assigned", thresholds.get(RideStatus.DRIVER_ASSIGNED).toSeconds(),
                "arrived", thresholds.get(RideStatus.DRIVER_ARRIVED).toSeconds(),
                "inTrip", thresholds.get(RideStatus.IN_TRIP).toSeconds());
    }

    /** Riders and drivers with more than one active ride (I1), in the city or in all when {@code cityId} is null. */
    public List<String> partiesInTwoActiveRides(String cityId) {
        return jdbc.sql("""
                        SELECT 'rider ' || rider_id || ' has ' || count(*) || ' active rides' FROM ride.rides
                        WHERE status IN (:active) AND (CAST(:cityId AS text) IS NULL OR city_id = :cityId)
                        GROUP BY rider_id HAVING count(*) > 1
                        UNION ALL
                        SELECT 'driver ' || driver_id || ' has ' || count(*) || ' active rides' FROM ride.rides
                        WHERE status IN (:assigned) AND (CAST(:cityId AS text) IS NULL OR city_id = :cityId)
                        GROUP BY driver_id HAVING count(*) > 1
                        """)
                .param("active", ACTIVE)
                .param("assigned", ACTIVE.subList(1, ACTIVE.size()))
                .param("cityId", cityId)
                .query(String.class)
                .list();
    }

    private static RideRow ride(ResultSet row, int rowNumber) throws SQLException {
        return new RideRow(row.getObject("id", UUID.class), row.getObject("rider_id", UUID.class),
                row.getString("city_id"), row.getString("category"), row.getObject("quote_id", UUID.class),
                new GeoPoint(row.getDouble("pickup_lat"), row.getDouble("pickup_lon")),
                new GeoPoint(row.getDouble("dropoff_lat"), row.getDouble("dropoff_lon")), row.getString("pickup_zone"),
                row.getLong("fare_paise"), row.getString("currency"), row.getObject("payment_method_id", UUID.class),
                row.getString("payment_method_type"), RideStatus.valueOf(row.getString("status")),
                row.getInt("version"), row.getInt("search_generation"), row.getObject("driver_id", UUID.class),
                row.getObject("vehicle_id", UUID.class), row.getObject("offer_id", UUID.class),
                row.getString("rider_snapshot"), row.getString("driver_snapshot"), row.getString("pin"),
                row.getObject("promised_pickup_eta_s", Integer.class), instant(row, "requested_at"),
                instant(row, "assigned_at"),
                instant(row, "arrived_at"), instant(row, "started_at"), instant(row, "completed_at"),
                instant(row, "ended_at"), row.getString("cancelled_by"), row.getString("cancel_reason"),
                row.getInt("reassign_count"), row.getLong("commission_paise"), row.getObject("fee_rule_id", UUID.class),
                row.getInt("pin_attempts"), row.getString("fee_purpose"), row.getObject("fee_paise", Long.class),
                row.getInt("distance_m"), row.getInt("duration_s"), row.getString("fare_breakdown"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** {@code fareBreakdown} is the quote's, as JSON. */
    public record NewRide(UUID id, UUID riderId, String cityId, String category, UUID quoteId, GeoPoint pickup,
            GeoPoint dropoff, String pickupZone, int distanceM, int durationS, long farePaise, long commissionPaise,
            String currency, UUID feeRuleId, UUID paymentMethodId, String paymentMethodType, String riderSnapshot,
            String fareBreakdown) {
    }

    /** Snapshots and the fare breakdown are JSON text; nullable columns are null until their transition. */
    public record RideRow(UUID id, UUID riderId, String cityId, String category, UUID quoteId, GeoPoint pickup,
            GeoPoint dropoff, String pickupZone, long farePaise, String currency, UUID paymentMethodId,
            String paymentMethodType, RideStatus status, int version, int searchGeneration, UUID driverId,
            UUID vehicleId, UUID offerId, String riderSnapshot, String driverSnapshot, String pin,
            Integer promisedPickupEtaS, Instant requestedAt, Instant assignedAt, Instant arrivedAt, Instant startedAt,
            Instant completedAt, Instant endedAt, String cancelledBy, String cancelReason, int reassignCount,
            long commissionPaise, UUID feeRuleId, int pinAttempts, String feePurpose, Long feePaise, int distanceM,
            int durationS, String fareBreakdown) {
    }

    public record OverdueRide(UUID rideId, RideStatus status, Instant since) {
    }
}
