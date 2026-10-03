package com.ridehailing.ride.db;

import com.ridehailing.ride.RideStatus;
import com.ridehailing.shared.GeoPoint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Rides (LLD §4.5). Every change adds 1 to the version and is guarded by the expected one (ADR-010). */
@Repository
public class RideRepository {

    private static final String RETURNED = """
            id, rider_id, city_id, category, quote_id, pickup_lat, pickup_lon, dropoff_lat, dropoff_lon, pickup_zone,
            fare_paise, currency, payment_method_id, payment_method_type, status, version, search_generation,
            driver_id, vehicle_id, offer_id, rider_snapshot::text AS rider_snapshot,
            driver_snapshot::text AS driver_snapshot, pin, promised_pickup_eta_s, requested_at, assigned_at,
            arrived_at, started_at, completed_at, ended_at, cancelled_by, cancel_reason, reassign_count
            """;
    private static final String COLUMNS = "SELECT " + RETURNED + " FROM ride.rides ";
    private static final List<String> ACTIVE = List.of("SEARCHING", "DRIVER_ASSIGNED", "DRIVER_ARRIVED", "IN_TRIP");

    private final JdbcClient jdbc;

    RideRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A ride in {@code SEARCHING}, requested now. */
    public RideRow insert(NewRide ride) {
        return jdbc.sql("""
                        INSERT INTO ride.rides (id, rider_id, city_id, category, quote_id, pickup_lat, pickup_lon,
                                                dropoff_lat, dropoff_lon, pickup_zone, distance_m, duration_s,
                                                fare_paise, commission_paise, currency, fee_rule_id,
                                                payment_method_id, payment_method_type, status, rider_snapshot,
                                                requested_at)
                        VALUES (:id, :riderId, :cityId, :category, :quoteId, :pickupLat, :pickupLon, :dropoffLat,
                                :dropoffLon, :pickupZone, :distanceM, :durationS, :farePaise, :commissionPaise,
                                :currency, :feeRuleId, :paymentMethodId, :paymentMethodType, 'SEARCHING',
                                CAST(:riderSnapshot AS jsonb), now())
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
        return jdbc.sql("""
                        UPDATE ride.rides
                        SET status = :to, cancelled_by = :cancelledBy, cancel_reason = :reason, ended_at = now(),
                            version = version + 1
                        WHERE id = :id AND status = 'SEARCHING' AND version = :version
                        RETURNING
                        """ + RETURNED)
                .param("to", to.name())
                .param("cancelledBy", cancelledBy)
                .param("reason", reason)
                .param("id", id)
                .param("version", version)
                .query(RideRepository::ride)
                .optional();
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
                row.getInt("reassign_count"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record NewRide(UUID id, UUID riderId, String cityId, String category, UUID quoteId, GeoPoint pickup,
            GeoPoint dropoff, String pickupZone, int distanceM, int durationS, long farePaise, long commissionPaise,
            String currency, UUID feeRuleId, UUID paymentMethodId, String paymentMethodType, String riderSnapshot) {
    }

    /** Snapshots are JSON text; nullable columns are null until their transition. */
    public record RideRow(UUID id, UUID riderId, String cityId, String category, UUID quoteId, GeoPoint pickup,
            GeoPoint dropoff, String pickupZone, long farePaise, String currency, UUID paymentMethodId,
            String paymentMethodType, RideStatus status, int version, int searchGeneration, UUID driverId,
            UUID vehicleId, UUID offerId, String riderSnapshot, String driverSnapshot, String pin,
            Integer promisedPickupEtaS, Instant requestedAt, Instant assignedAt, Instant arrivedAt, Instant startedAt,
            Instant completedAt, Instant endedAt, String cancelledBy, String cancelReason, int reassignCount) {
    }
}
