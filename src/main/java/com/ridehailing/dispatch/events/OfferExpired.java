package com.ridehailing.dispatch.events;

import java.time.Instant;
import java.util.UUID;

/** The offer timer fired while the offer was pending (docs/schemas/events/OfferExpired.v1.json). */
public record OfferExpired(UUID offerId, UUID rideId, UUID driverId, boolean seen, Instant expiredAt) {

    public static final String TYPE = "OfferExpired";
    public static final int VERSION = 1;
}
