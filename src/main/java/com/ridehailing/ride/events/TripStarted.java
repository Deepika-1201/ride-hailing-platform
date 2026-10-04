package com.ridehailing.ride.events;

import java.time.Instant;
import java.util.UUID;

/** T9 (docs/schemas/events/TripStarted.v1.json); the device time arrives with offline commands in V2. */
public record TripStarted(UUID rideId, UUID riderId, UUID driverId, Instant startedAt) {

    public static final String TYPE = "TripStarted";
    public static final int VERSION = 1;
}
