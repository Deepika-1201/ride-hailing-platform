package com.ridehailing.driver.events;

import java.time.Instant;
import java.util.UUID;

/** Operations suspended the driver (docs/schemas/events/DriverSuspended.v1.json). */
public record DriverSuspended(UUID driverId, String reason, UUID by, Instant at) {

    public static final String TYPE = "DriverSuspended";
    public static final int VERSION = 1;
}
