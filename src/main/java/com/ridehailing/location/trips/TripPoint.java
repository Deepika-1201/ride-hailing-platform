package com.ridehailing.location.trips;

import com.ridehailing.location.LiveIndex.LocationUpdate;
import java.time.Instant;
import java.util.UUID;

/** One row of {@code location.trip_points} (LLD §4.8); {@code speedMps} and {@code headingDeg} may be null. */
public record TripPoint(UUID rideId, long seq, UUID driverId, Instant receivedAt, Instant deviceTime, double lat,
        double lon, double accuracyM, Double speedMps, Double headingDeg, int flags) {

    /** Sent from the app's offline store (§9.6); 1 and 2 are the quality flags of {@code UpdateResult}. */
    public static final int REPLAYED = 4;

    public static TripPoint of(UUID rideId, UUID driverId, Instant receivedAt, LocationUpdate update, int flags) {
        return new TripPoint(rideId, update.seq(), driverId, receivedAt, update.deviceTime(), update.position().lat(),
                update.position().lon(), update.accuracyM(), update.speedMps(), update.headingDeg(), flags);
    }
}
