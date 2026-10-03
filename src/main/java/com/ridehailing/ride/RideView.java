package com.ridehailing.ride;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A ride as the Ride schema shows it. {@link #forRider()} and {@link #forDriver()} give each party its own view: the
 * PIN is the rider's alone (FR-RD5), and the rider summary is for the driver.
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
        Instant requestedAt,
        Instant assignedAt,
        Instant arrivedAt,
        Instant startedAt,
        Instant completedAt,
        Instant endedAt,
        @JsonIgnore UUID riderId,
        @JsonIgnore UUID driverId,
        @JsonIgnore UUID offerId) {

    /** The PIN only from assignment until the trip starts; no summary of the rider themselves. */
    public RideView forRider() {
        boolean pinShown = status == RideStatus.DRIVER_ASSIGNED || status == RideStatus.DRIVER_ARRIVED;
        return new RideView(id, status, version, cityId, category, pickup, dropoff, fare, paymentMethod,
                pinShown ? pin : null, promisedPickupEtaS, driver, vehicle, null, cancellation, requestedAt,
                assignedAt, arrivedAt, startedAt, completedAt, endedAt, riderId, driverId, offerId);
    }

    /** Never the PIN. */
    public RideView forDriver() {
        return new RideView(id, status, version, cityId, category, pickup, dropoff, fare, paymentMethod, null,
                promisedPickupEtaS, driver, vehicle, rider, cancellation, requestedAt, assignedAt, arrivedAt,
                startedAt, completedAt, endedAt, riderId, driverId, offerId);
    }

    public record PaymentMethodRef(UUID id, String type) {
    }

    /** Ratings join it in phase 10. */
    public record PersonSummary(UUID id, String firstName) {
    }

    public record VehicleSummary(UUID id, String category, String make, String model, String colour, String plate) {
    }

    /** {@code reason} may be null. */
    public record Cancellation(String cancelledBy, String reason) {
    }
}
