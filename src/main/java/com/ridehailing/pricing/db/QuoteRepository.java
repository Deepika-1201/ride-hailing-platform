package com.ridehailing.pricing.db;

import com.ridehailing.shared.GeoPoint;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Quotes: immutable once created, except for being used by one ride (LLD §4.4, §7.2). */
@Repository
public class QuoteRepository {

    private static final String COLUMNS = """
            id, rider_id, city_id, category, pickup_lat, pickup_lon, dropoff_lat, dropoff_lon, pickup_zone,
            distance_m, duration_s, route_source, fare_rule_id, fee_rule_id, surge_multiplier, surge_source,
            base_paise, distance_paise, time_paise, surge_paise, minimum_topup_paise, booking_fee_paise, tax_paise,
            rounding_paise, total_paise, commission_paise, currency, pickup_eta_s, created_at, expires_at,
            used_by_ride_id
            """;

    private final JdbcClient jdbc;

    QuoteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Created now and expiring after {@code ttl}, by the database clock; the given times are ignored. */
    public QuoteRow insert(QuoteRow quote, Duration ttl) {
        Fare fare = quote.fare();
        return jdbc.sql("""
                        INSERT INTO pricing.quotes (id, rider_id, city_id, category, pickup_lat, pickup_lon, dropoff_lat,
                                                    dropoff_lon, pickup_zone, distance_m, duration_s, route_source,
                                                    fare_rule_id, fee_rule_id, surge_multiplier, surge_source,
                                                    base_paise, distance_paise, time_paise, surge_paise,
                                                    minimum_topup_paise, booking_fee_paise, tax_paise, rounding_paise,
                                                    total_paise, commission_paise, currency, pickup_eta_s, created_at,
                                                    expires_at)
                        VALUES (:id, :riderId, :cityId, :category, :pickupLat, :pickupLon, :dropoffLat, :dropoffLon,
                                :pickupZone, :distanceM, :durationS, :routeSource, :fareRuleId, :feeRuleId,
                                :surgeMultiplier, :surgeSource, :base, :distance, :time, :surge, :minimumTopup,
                                :bookingFee, :tax, :rounding, :total, :commission, :currency, :pickupEtaS, now(),
                                now() + make_interval(secs => :ttlSeconds))
                        RETURNING""" + " " + COLUMNS)
                .param("id", quote.id())
                .param("riderId", quote.riderId())
                .param("cityId", quote.cityId())
                .param("category", quote.category())
                .param("pickupLat", quote.pickup().lat())
                .param("pickupLon", quote.pickup().lon())
                .param("dropoffLat", quote.dropoff().lat())
                .param("dropoffLon", quote.dropoff().lon())
                .param("pickupZone", quote.pickupZone())
                .param("distanceM", quote.distanceM())
                .param("durationS", quote.durationS())
                .param("routeSource", quote.routeSource())
                .param("fareRuleId", quote.fareRuleId())
                .param("feeRuleId", quote.feeRuleId())
                .param("surgeMultiplier", quote.surgeMultiplier())
                .param("surgeSource", quote.surgeSource())
                .param("base", fare.base())
                .param("distance", fare.distance())
                .param("time", fare.time())
                .param("surge", fare.surge())
                .param("minimumTopup", fare.minimumTopup())
                .param("bookingFee", fare.bookingFee())
                .param("tax", fare.tax())
                .param("rounding", fare.rounding())
                .param("total", fare.total())
                .param("commission", fare.commission())
                .param("currency", quote.currency())
                .param("pickupEtaS", quote.pickupEtaS())
                .param("ttlSeconds", (double) ttl.toSeconds())
                .query(QuoteRepository::quote)
                .single();
    }

    public Optional<QuoteRow> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM pricing.quotes WHERE id = :id")
                .param("id", id)
                .query(QuoteRepository::quote)
                .optional();
    }

    /** Marks the rider's quote used by the ride if it is unused and unexpired, by the database clock. */
    public Optional<QuoteRow> use(UUID id, UUID riderId, UUID rideId) {
        return jdbc.sql("""
                        UPDATE pricing.quotes SET used_by_ride_id = :rideId, used_at = now()
                        WHERE id = :id AND rider_id = :riderId AND used_by_ride_id IS NULL AND expires_at > now()
                        RETURNING""" + " " + COLUMNS)
                .param("rideId", rideId)
                .param("id", id)
                .param("riderId", riderId)
                .query(QuoteRepository::quote)
                .optional();
    }

    /** Deletes up to {@code batch} unused quotes created before {@code unusedBefore} ago and others before
     * {@code usedBefore} ago; returns how many. */
    public int deleteOld(Duration unusedBefore, Duration usedBefore, int batch) {
        return jdbc.sql("""
                        DELETE FROM pricing.quotes WHERE id IN (
                            SELECT id FROM pricing.quotes
                            WHERE created_at < now() - make_interval(secs => :usedSeconds)
                               OR (created_at < now() - make_interval(secs => :unusedSeconds) AND used_by_ride_id IS NULL)
                            LIMIT :batch)
                        """)
                .param("usedSeconds", (double) usedBefore.toSeconds())
                .param("unusedSeconds", (double) unusedBefore.toSeconds())
                .param("batch", batch)
                .update();
    }

    private static QuoteRow quote(ResultSet row, int rowNumber) throws SQLException {
        Fare fare = new Fare(row.getLong("base_paise"), row.getLong("distance_paise"), row.getLong("time_paise"),
                row.getLong("surge_paise"), row.getLong("minimum_topup_paise"), row.getLong("booking_fee_paise"),
                row.getLong("tax_paise"), row.getLong("rounding_paise"), row.getLong("total_paise"),
                row.getLong("commission_paise"));
        return new QuoteRow(row.getObject("id", UUID.class), row.getObject("rider_id", UUID.class),
                row.getString("city_id"), row.getString("category"),
                new GeoPoint(row.getDouble("pickup_lat"), row.getDouble("pickup_lon")),
                new GeoPoint(row.getDouble("dropoff_lat"), row.getDouble("dropoff_lon")),
                row.getString("pickup_zone"), row.getInt("distance_m"), row.getInt("duration_s"),
                row.getString("route_source"), row.getObject("fare_rule_id", UUID.class),
                row.getObject("fee_rule_id", UUID.class), row.getBigDecimal("surge_multiplier"),
                row.getString("surge_source"), fare, row.getString("currency"),
                row.getObject("pickup_eta_s", Integer.class),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                row.getObject("used_by_ride_id", UUID.class));
    }

    /** {@code pickupEtaS} and {@code usedByRideId} may be null. */
    public record QuoteRow(UUID id, UUID riderId, String cityId, String category, GeoPoint pickup, GeoPoint dropoff,
            String pickupZone, int distanceM, int durationS, String routeSource, UUID fareRuleId, UUID feeRuleId,
            BigDecimal surgeMultiplier, String surgeSource, Fare fare, String currency, Integer pickupEtaS,
            Instant createdAt, Instant expiresAt, UUID usedByRideId) {
    }
}
