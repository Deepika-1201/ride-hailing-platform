package com.ridehailing.ride.events;

import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Money;
import java.time.Instant;
import java.util.UUID;

/** A rider booked a ride (T1; docs/schemas/events/RideRequested.v1.json). */
public record RideRequested(UUID rideId, UUID riderId, String cityId, String category, GeoPoint pickup,
        GeoPoint dropoff, String pickupZone, Money fare, String paymentMethodType, UUID quoteId, Instant requestedAt) {

    public static final String TYPE = "RideRequested";
    public static final int VERSION = 1;
}
