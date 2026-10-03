package com.ridehailing.dispatch.db;

import com.ridehailing.dispatch.AvailabilityStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

/** One availability row per driver, kept while offline; every change adds 1 to the version (LLD §4.6, §8.1). */
@Repository
public class AvailabilityRepository {

    private static final String RETURNED = """
            driver_id, city_id, status, category, vehicle_id, offer_id, ride_id, consecutive_expired, online_since,
            status_changed_at, version
            """;
    private static final String COLUMNS = "SELECT " + RETURNED + " FROM dispatch.driver_availability ";

    private final JdbcClient jdbc;

    AvailabilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AvailabilityRow> find(UUID driverId) {
        return jdbc.sql(COLUMNS + "WHERE driver_id = :driverId").param("driverId", driverId)
                .query(AvailabilityRepository::row).optional();
    }

    public Optional<AvailabilityRow> lock(UUID driverId) {
        return jdbc.sql(COLUMNS + "WHERE driver_id = :driverId FOR UPDATE").param("driverId", driverId)
                .query(AvailabilityRepository::row).optional();
    }

    /** The row a driver gets the first time they go online. */
    public void insertOfflineIfAbsent(UUID driverId, String cityId) {
        jdbc.sql("""
                        INSERT INTO dispatch.driver_availability (driver_id, city_id, status, status_changed_at)
                        VALUES (:driverId, :cityId, 'OFFLINE', now())
                        ON CONFLICT (driver_id) DO NOTHING
                        """)
                .param("driverId", driverId)
                .param("cityId", cityId)
                .update();
    }

    /** {@code OFFLINE → AVAILABLE}; empty if the driver isn't offline. */
    public Optional<AvailabilityRow> goOnline(UUID driverId, String cityId, String category, UUID vehicleId) {
        return jdbc.sql("""
                        UPDATE dispatch.driver_availability
                        SET status = 'AVAILABLE', city_id = :cityId, category = :category, vehicle_id = :vehicleId,
                            online_since = now(), consecutive_expired = 0, status_changed_at = now(),
                            version = version + 1
                        WHERE driver_id = :driverId AND status = 'OFFLINE'
                        RETURNING
                        """ + RETURNED)
                .param("driverId", driverId)
                .param("cityId", cityId)
                .param("category", category)
                .param("vehicleId", vehicleId)
                .query(AvailabilityRepository::row)
                .optional();
    }

    /** Any status to {@code OFFLINE}, clearing what only applies online; the caller holds the row's lock. */
    public AvailabilityRow goOffline(UUID driverId) {
        return jdbc.sql("""
                        UPDATE dispatch.driver_availability
                        SET status = 'OFFLINE', category = NULL, vehicle_id = NULL, offer_id = NULL, ride_id = NULL,
                            online_since = NULL, consecutive_expired = 0, offline_after_ride = false,
                            status_changed_at = now(), version = version + 1
                        WHERE driver_id = :driverId
                        RETURNING
                        """ + RETURNED)
                .param("driverId", driverId)
                .query(AvailabilityRepository::row)
                .single();
    }

    /**
     * {@code AVAILABLE → OFFERED} for a driver of the city and category (§8.3); empty if another search reserved
     * them first, or they moved on.
     */
    public Optional<AvailabilityRow> reserve(UUID driverId, UUID offerId, String cityId, String category) {
        return jdbc.sql("""
                        UPDATE dispatch.driver_availability
                        SET status = 'OFFERED', offer_id = :offerId, status_changed_at = now(), version = version + 1
                        WHERE driver_id = :driverId AND status = 'AVAILABLE' AND city_id = :cityId
                          AND category = :category
                        RETURNING
                        """ + RETURNED)
                .param("offerId", offerId)
                .param("driverId", driverId)
                .param("cityId", cityId)
                .param("category", category)
                .query(AvailabilityRepository::row)
                .optional();
    }

    /** {@code OFFERED → ASSIGNED} through this offer (§8.4); empty if the driver no longer holds it. */
    public Optional<AvailabilityRow> assign(UUID driverId, UUID offerId, UUID rideId) {
        return jdbc.sql("""
                        UPDATE dispatch.driver_availability
                        SET status = 'ASSIGNED', ride_id = :rideId, offer_id = NULL, consecutive_expired = 0,
                            status_changed_at = now(), version = version + 1
                        WHERE driver_id = :driverId AND status = 'OFFERED' AND offer_id = :offerId
                        RETURNING
                        """ + RETURNED)
                .param("rideId", rideId)
                .param("driverId", driverId)
                .param("offerId", offerId)
                .query(AvailabilityRepository::row)
                .optional();
    }

    /**
     * {@code OFFERED → AVAILABLE} when this offer ends without acceptance; a null {@code consecutiveExpired} keeps the
     * count. Empty if the driver doesn't hold the offer.
     */
    public Optional<AvailabilityRow> release(UUID driverId, UUID offerId, Integer consecutiveExpired) {
        return jdbc.sql("""
                        UPDATE dispatch.driver_availability
                        SET status = 'AVAILABLE', offer_id = NULL,
                            consecutive_expired = coalesce(CAST(:expired AS smallint), consecutive_expired),
                            status_changed_at = now(), version = version + 1
                        WHERE driver_id = :driverId AND status = 'OFFERED' AND offer_id = :offerId
                        RETURNING
                        """ + RETURNED)
                .param("expired", consecutiveExpired)
                .param("driverId", driverId)
                .param("offerId", offerId)
                .query(AvailabilityRepository::row)
                .optional();
    }

    /**
     * Drivers whose offer isn't their pending offer (I3). A CHECK ties {@code OFFERED} to having an offer, so this
     * also finds an {@code OFFERED} driver without a pending offer and a pending offer on a driver who isn't.
     */
    public List<String> offeredWithoutTheirOffer(String cityId) {
        return jdbc.sql("""
                        SELECT 'driver ' || a.driver_id || ' is ' || a.status || ' with offer '
                               || coalesce(a.offer_id::text, 'none') || ', pending offer '
                               || coalesce(o.id::text, 'none')
                        FROM dispatch.driver_availability a
                        LEFT JOIN dispatch.offers o ON o.driver_id = a.driver_id AND o.status = 'PENDING'
                        WHERE (CAST(:cityId AS text) IS NULL OR a.city_id = :cityId)
                          AND a.offer_id IS DISTINCT FROM o.id
                        """)
                .param("cityId", cityId)
                .query(String.class)
                .list();
    }

    public List<AvailabilityRow> online(String cityId) {
        return jdbc.sql(COLUMNS + "WHERE city_id = :cityId AND status <> 'OFFLINE'").param("cityId", cityId)
                .query(AvailabilityRepository::row).list();
    }

    public List<AvailabilityRow> ofDrivers(Collection<UUID> driverIds) {
        return jdbc.sql(COLUMNS + "WHERE driver_id = ANY(:driverIds)")
                .param("driverIds", new SqlArrayValue("uuid", driverIds.toArray()))
                .query(AvailabilityRepository::row)
                .list();
    }

    public List<String> citiesWithOnlineDrivers() {
        return jdbc.sql("SELECT DISTINCT city_id FROM dispatch.driver_availability WHERE status <> 'OFFLINE'")
                .query(String.class).list();
    }

    /** Cities with online drivers, or with a driver who went offline within {@code recently}. */
    public List<String> citiesWithChanges(Duration recently) {
        return jdbc.sql("""
                        SELECT DISTINCT city_id FROM dispatch.driver_availability
                        WHERE status <> 'OFFLINE' OR status_changed_at > now() - make_interval(secs => :seconds)
                        """)
                .param("seconds", recently.toSeconds())
                .query(String.class)
                .list();
    }

    private static AvailabilityRow row(ResultSet row, int rowNumber) throws SQLException {
        OffsetDateTime onlineSince = row.getObject("online_since", OffsetDateTime.class);
        return new AvailabilityRow(row.getObject("driver_id", UUID.class), row.getString("city_id"),
                AvailabilityStatus.valueOf(row.getString("status")), row.getString("category"),
                row.getObject("vehicle_id", UUID.class), row.getObject("offer_id", UUID.class),
                row.getObject("ride_id", UUID.class), row.getInt("consecutive_expired"),
                onlineSince == null ? null : onlineSince.toInstant(),
                row.getObject("status_changed_at", OffsetDateTime.class).toInstant(), row.getLong("version"));
    }

    /** {@code consecutiveExpired} counts seen offers that expired in a row (§8.6). */
    public record AvailabilityRow(UUID driverId, String cityId, AvailabilityStatus status, String category,
            UUID vehicleId, UUID offerId, UUID rideId, int consecutiveExpired, Instant onlineSince,
            Instant statusChangedAt, long version) {
    }
}
