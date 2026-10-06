package com.ridehailing.rating.events;

import java.time.Instant;
import java.util.UUID;

/** docs/schemas/events/RatingSubmitted.v1.json: the comment stays in the rating module. */
public record RatingSubmitted(UUID ratingId, UUID rideId, String raterRole, UUID raterId, UUID rateeId, int stars,
        boolean hasComment, Instant submittedAt) {

    public static final String TYPE = "RatingSubmitted";
    public static final int VERSION = 1;
}
