package com.ridehailing.ride;

import com.ridehailing.shared.Actor;
import java.util.Optional;
import java.util.UUID;

/** Operations' commands on rides (LLD §2.2), for the operations module only. */
public interface RideOperations {

    /**
     * T13: cancels a ride that hasn't ended, with an optional fee of at most the ride's fee rule: {@code 409
     * INVALID_TRANSITION} once it ended, {@code 422 FEE_EXCEEDS_RULE} above the rule.
     */
    RideView cancelBySystem(UUID rideId, Actor ops, String reason, Optional<FeeRequest> fee);

    /** {@code purpose} is {@code CANCELLATION_FEE} or {@code NO_SHOW_FEE}. */
    record FeeRequest(String purpose, long amountPaise) {
    }
}
