package com.ridehailing.dispatch.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The driver went offline, or the platform took them offline; {@code reason} is the session's offline reason
 * (docs/schemas/events/DriverWentOffline.v1.json).
 */
public record DriverWentOffline(UUID driverId, String cityId, String reason, long onlineSeconds, Instant offlineAt) {

    public static final String TYPE = "DriverWentOffline";
    public static final int VERSION = 1;
}
