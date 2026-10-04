package com.ridehailing.ride.events;

import java.time.Instant;
import java.util.UUID;

/**
 * T6 and T7 (docs/schemas/events/DriverUnassigned.v1.json): the ride searches again, as {@code searchGeneration}.
 * {@code reason} is {@code DRIVER_CANCELLED} or {@code DRIVER_UNREACHABLE}.
 */
public record DriverUnassigned(UUID rideId, UUID riderId, UUID driverId, String reason, int searchGeneration,
        Instant unassignedAt) {

    public static final String TYPE = "DriverUnassigned";
    public static final int VERSION = 1;
}
