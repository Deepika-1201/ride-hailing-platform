package com.ridehailing.dispatch.events;

import java.time.Instant;
import java.util.UUID;

/** A search attempt reserved a driver and offered the ride (docs/schemas/events/OfferCreated.v1.json). */
public record OfferCreated(UUID offerId, UUID rideId, UUID driverId, int attempt, int rank, int distanceM,
        String strategy, Instant expiresAt) {

    public static final String TYPE = "OfferCreated";
    public static final int VERSION = 1;
}
