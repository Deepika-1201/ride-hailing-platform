package com.ridehailing.ride.events;

import java.time.Instant;
import java.util.UUID;

/** A driver accepted the ride's offer (T2; docs/schemas/events/DriverAssigned.v1.json). Never carries the PIN. */
public record DriverAssigned(UUID rideId, UUID riderId, UUID driverId, UUID vehicleId, UUID offerId,
        int promisedPickupEtaS, int reassignCount, Instant assignedAt) {

    public static final String TYPE = "DriverAssigned";
    public static final int VERSION = 1;
}
