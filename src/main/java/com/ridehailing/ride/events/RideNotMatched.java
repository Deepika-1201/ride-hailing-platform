package com.ridehailing.ride.events;

import java.time.Instant;
import java.util.UUID;

/** No driver accepted before the search timeout (T3; docs/schemas/events/RideNotMatched.v1.json). */
public record RideNotMatched(UUID rideId, UUID riderId, String cityId, String category, long searchedForS,
        Instant endedAt) {

    public static final String TYPE = "RideNotMatched";
    public static final int VERSION = 1;
}
