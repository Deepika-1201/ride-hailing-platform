package com.ridehailing.payment.events;

import com.ridehailing.shared.Money;
import java.time.Instant;
import java.util.UUID;

/** docs/schemas/events/RefundSucceeded.v1.json: requested by operations, or automatic after a late success. */
public record RefundSucceeded(UUID refundId, UUID chargeId, UUID rideId, UUID riderId, Money amount, boolean automatic,
        Instant succeededAt) {

    public static final String TYPE = "RefundSucceeded";
    public static final int VERSION = 1;
}
