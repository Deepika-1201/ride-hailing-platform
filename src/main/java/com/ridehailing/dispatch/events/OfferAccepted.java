package com.ridehailing.dispatch.events;

import java.time.Instant;
import java.util.UUID;

/** The driver accepted, in DriverAssigned's transaction (docs/schemas/events/OfferAccepted.v1.json). */
public record OfferAccepted(UUID offerId, UUID rideId, UUID driverId, Instant respondedAt) {

    public static final String TYPE = "OfferAccepted";
    public static final int VERSION = 1;
}
