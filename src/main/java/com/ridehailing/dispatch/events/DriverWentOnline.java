package com.ridehailing.dispatch.events;

import java.time.Instant;
import java.util.UUID;

/** The driver went online with a vehicle (docs/schemas/events/DriverWentOnline.v1.json). */
public record DriverWentOnline(UUID driverId, String cityId, String category, UUID vehicleId, Instant onlineAt) {

    public static final String TYPE = "DriverWentOnline";
    public static final int VERSION = 1;
}
