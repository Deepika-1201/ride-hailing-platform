package com.ridehailing.driver.events;

import java.time.Instant;
import java.util.UUID;

/** An admin verified the driver, who may now go online (docs/schemas/events/DriverVerified.v1.json). */
public record DriverVerified(UUID driverId, String cityId, Instant verifiedAt, UUID verifiedBy) {

    public static final String TYPE = "DriverVerified";
    public static final int VERSION = 1;
}
