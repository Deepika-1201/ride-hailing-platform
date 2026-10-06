package com.ridehailing.driver.events;

import java.time.Instant;
import java.util.UUID;

/** Operations lifted the driver's suspension (docs/schemas/events/DriverReinstated.v1.json). */
public record DriverReinstated(UUID driverId, String reason, UUID by, Instant at) {

    public static final String TYPE = "DriverReinstated";
    public static final int VERSION = 1;
}
