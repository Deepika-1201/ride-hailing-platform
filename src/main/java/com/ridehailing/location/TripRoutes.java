package com.ridehailing.location;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Rides' trip routes (LLD §9.8, ADR-016); callers check who may read them. */
public interface TripRoutes {

    /**
     * The ride's points in sequence order, each sequence number once, thinned to at most {@code maxPoints} with the
     * first and the last kept.
     */
    TripRoute route(UUID rideId, int maxPoints);

    /** The TripRoute schema of {@code openapi.yaml}; {@code thinned} says whether points were left out. */
    record TripRoute(UUID rideId, boolean thinned, List<RoutePoint> points) {
    }

    record RoutePoint(long seq, double lat, double lon, Instant receivedAt) {
    }
}
