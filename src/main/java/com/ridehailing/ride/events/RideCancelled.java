package com.ridehailing.ride.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The ride ended without a trip (docs/schemas/events/RideCancelled.v1.json). {@code driverId} and {@code reason} may
 * be null; fees join it with the cancellations that charge them in phase 8.
 */
public record RideCancelled(UUID rideId, UUID riderId, UUID driverId, String cityId, String status,
        String cancelledBy, String reason, UUID paymentMethodId, String paymentMethodType, Instant cancelledAt) {

    public static final String TYPE = "RideCancelled";
    public static final int VERSION = 1;
}
