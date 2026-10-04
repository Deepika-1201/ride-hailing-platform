package com.ridehailing.ride.events;

import com.ridehailing.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * The ride ended without a trip (docs/schemas/events/RideCancelled.v1.json). {@code driverId}, {@code reason} and
 * {@code fee} may be null.
 */
public record RideCancelled(UUID rideId, UUID riderId, UUID driverId, String cityId, String status,
        String cancelledBy, String reason, Fee fee, UUID paymentMethodId, String paymentMethodType,
        Instant cancelledAt) {

    public static final String TYPE = "RideCancelled";
    public static final int VERSION = 1;

    /** The fee decision and the rule that produced it (LLD §7.4). */
    public record Fee(String purpose, Money amount, Money commission, UUID feeRuleId) {
    }
}
