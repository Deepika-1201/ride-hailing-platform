package com.ridehailing.ride.events;

import java.time.Instant;
import java.util.UUID;

/** T5 (docs/schemas/events/DriverArrived.v1.json); {@code distanceToPickupM} is null without a live position. */
public record DriverArrived(UUID rideId, UUID riderId, UUID driverId, Integer distanceToPickupM, Instant arrivedAt) {

    public static final String TYPE = "DriverArrived";
    public static final int VERSION = 1;
}
