package com.ridehailing.ride;

import com.ridehailing.platform.Cursor;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.Page;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Operations' commands on rides and the review queue (LLD §2.2, §13.5), for the operations module only. */
public interface RideOperations {

    /**
     * T13: cancels a ride that hasn't ended, with an optional fee of at most the ride's fee rule: {@code 409
     * INVALID_TRANSITION} once it ended, {@code 422 FEE_EXCEEDS_RULE} above the rule.
     */
    RideView cancelBySystem(UUID rideId, Actor ops, String reason, Optional<FeeRequest> fee);

    /** The review queue, newest first after the cursor. */
    Page<FlagView> flags(FlagFilter filter, Cursor after, int limit);

    /** The ride's flags, oldest first. */
    List<FlagView> flagsOfRide(UUID rideId);

    /** {@code 404} for an unknown flag, {@code 409 FLAG_ALREADY_RESOLVED} for a resolved one. */
    FlagView resolveFlag(UUID flagId, Actor ops, String resolution);

    /** {@code purpose} is {@code CANCELLATION_FEE} or {@code NO_SHOW_FEE}. */
    record FeeRequest(String purpose, long amountPaise) {
    }

    /** {@code kind} null: every kind. */
    record FlagFilter(boolean open, String kind) {
    }

    /** The Flag schema of {@code openapi.yaml}. */
    record FlagView(UUID id, UUID rideId, String kind, JsonNode details, Instant createdAt, Instant resolvedAt,
            String resolution) {
    }
}
