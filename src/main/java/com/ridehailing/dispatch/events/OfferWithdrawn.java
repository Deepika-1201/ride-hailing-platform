package com.ridehailing.dispatch.events;

import java.time.Instant;
import java.util.UUID;

/** The platform ended a pending offer (docs/schemas/events/OfferWithdrawn.v1.json). */
public record OfferWithdrawn(UUID offerId, UUID rideId, UUID driverId, String reason, Instant withdrawnAt) {

    public static final String TYPE = "OfferWithdrawn";
    public static final int VERSION = 1;
}
