package com.ridehailing.dispatch.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The driver declined ({@code DRIVER}), or went offline with the offer pending ({@code DRIVER_OFFLINE})
 * (docs/schemas/events/OfferDeclined.v1.json).
 */
public record OfferDeclined(UUID offerId, UUID rideId, UUID driverId, String reason, Instant respondedAt) {

    public static final String TYPE = "OfferDeclined";
    public static final int VERSION = 1;
}
