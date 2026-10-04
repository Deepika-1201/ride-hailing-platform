package com.ridehailing.payment.events;

import com.ridehailing.shared.Money;
import java.time.Instant;
import java.util.UUID;

/** docs/schemas/events/ChargeSucceeded.v1.json: online through an attempt, or recorded as cash without one. */
public record ChargeSucceeded(UUID chargeId, UUID rideId, UUID riderId, UUID driverId, String purpose, Money amount,
        String methodType, UUID attemptId, Instant succeededAt) {

    public static final String TYPE = "ChargeSucceeded";
    public static final int VERSION = 1;
}
