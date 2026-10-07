package com.ridehailing.ride;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ridehailing.rating.RatingApi.RatingSummary;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A ride as the Ride schema shows it. {@link #forRider()} and {@link #forDriver()} give each party its own view: the
 * PIN is the rider's alone (FR-RD5), the rider summary and the trip's earnings (FR-D4) are for the driver.
 */
public record RideView(
        UUID id,
        RideStatus status,
        int version,
        String cityId,
        String category,
        GeoPoint pickup,
        GeoPoint dropoff,
        Money fare,
        PaymentMethodRef paymentMethod,
        String pin,
        Integer promisedPickupEtaS,
        PersonSummary driver,
        VehicleSummary vehicle,
        PersonSummary rider,
        Cancellation cancellation,
        TripEarnings earnings,
        Instant requestedAt,
        Instant assignedAt,
        Instant arrivedAt,
        Instant startedAt,
        Instant completedAt,
        Instant endedAt,
        @JsonIgnore UUID riderId,
        @JsonIgnore UUID driverId,
        @JsonIgnore UUID offerId) {

    /** The PIN only from assignment until the trip starts; no summary of the rider themselves, no earnings. */
    public RideView forRider() {
        boolean pinShown = status == RideStatus.DRIVER_ASSIGNED || status == RideStatus.DRIVER_ARRIVED;
        return new RideView(id, status, version, cityId, category, pickup, dropoff, fare, paymentMethod,
                pinShown ? pin : null, promisedPickupEtaS, driver, vehicle, null, cancellation, null, requestedAt,
                assignedAt, arrivedAt, startedAt, completedAt, endedAt, riderId, driverId, offerId);
    }

    /** Never the PIN. */
    public RideView forDriver() {
        return new RideView(id, status, version, cityId, category, pickup, dropoff, fare, paymentMethod, null,
                promisedPickupEtaS, driver, vehicle, rider, cancellation, earnings, requestedAt, assignedAt, arrivedAt,
                startedAt, completedAt, endedAt, riderId, driverId, offerId);
    }

    /** For a driver who cancelled (T6, T11): no rider, no PIN, and not whoever drives the ride now (LLD §7.1). */
    public RideView forReleasedDriver() {
        return new RideView(id, status, version, cityId, category, pickup, dropoff, fare, paymentMethod, null,
                null, null, null, null, cancellation, null, requestedAt, null, null, null, null, endedAt, riderId,
                null, null);
    }

    /** Operations see everything but the PIN, as the driver does. */
    public RideView forOperations() {
        return forDriver();
    }

    public record PaymentMethodRef(UUID id, String type) {
    }

    /** {@code rating} is the person's when the ride took them: at booking for the rider, at assignment for the driver. */
    public record PersonSummary(UUID id, String firstName, RatingSummary rating) {
    }

    public record VehicleSummary(UUID id, String category, String make, String model, String colour, String plate) {
    }

    /** {@code reason} and {@code fee} may be null. */
    public record Cancellation(String cancelledBy, String reason, Fee fee) {
    }

    /** {@code CANCELLATION_FEE} or {@code NO_SHOW_FEE}. */
    public record Fee(String purpose, Money amount) {
    }

    /** A completed trip's row of earnings (LLD §11.8): {@code cashCollected} is the fare on a cash ride, else zero. */
    public record TripEarnings(Money gross, Money commission, Money net, Money cashCollected) {
    }
}
