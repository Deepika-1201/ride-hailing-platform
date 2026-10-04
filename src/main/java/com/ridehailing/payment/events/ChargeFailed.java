package com.ridehailing.payment.events;

import com.ridehailing.shared.Money;
import java.time.Instant;
import java.util.UUID;

/** docs/schemas/events/ChargeFailed.v1.json: the charge became rider dues; {@code attemptId} null without an attempt. */
public record ChargeFailed(UUID chargeId, UUID rideId, UUID riderId, String purpose, Money amount, String methodType,
        String failureCode, UUID attemptId, Instant failedAt) {

    public static final String TYPE = "ChargeFailed";
    public static final int VERSION = 1;
}
