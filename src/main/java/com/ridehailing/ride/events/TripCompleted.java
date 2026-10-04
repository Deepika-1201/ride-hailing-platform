package com.ridehailing.ride.events;

import com.ridehailing.shared.Money;
import java.time.Instant;
import java.util.UUID;

/** T12 (docs/schemas/events/TripCompleted.v1.json): what payment, earnings and ratings need; the fare is the quote's. */
public record TripCompleted(UUID rideId, UUID riderId, UUID driverId, String cityId, String category, Money fare,
        Money commission, UUID paymentMethodId, String paymentMethodType, Instant completedAt) {

    public static final String TYPE = "TripCompleted";
    public static final int VERSION = 1;
}
