package com.ridehailing.location;

import com.ridehailing.location.LiveIndex.LocationUpdate;
import com.ridehailing.location.LiveIndex.UpdateResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Location updates from drivers' apps (LLD §9.6): REST in V1, the WebSocket from V2. */
public interface LocationIngestion {

    /**
     * Applies the updates in sequence order for a driver online in the city with the category. Invalid and
     * out-of-bounds updates are ignored and counted. An applied live update of a driver the index has on a ride becomes
     * a trip point of that ride; a replayed one becomes a trip point of {@code replayedRide} at its device time,
     * whatever the index answered (§9.8).
     */
    BatchResult accept(String cityId, String category, UUID driverId, List<Incoming> updates,
            Function<Instant, Optional<UUID>> replayedRide);

    /** Live updates only. */
    default BatchResult accept(String cityId, String category, UUID driverId, List<LocationUpdate> updates) {
        return accept(cityId, category, driverId, updates.stream().map(update -> new Incoming(update, false)).toList(),
                deviceTime -> Optional.empty());
    }

    /**
     * One live update from the WebSocket (§9.6), as {@link #accept} takes it; answers what the index said, or nothing
     * for an invalid or out-of-bounds update.
     */
    Optional<UpdateResult> live(String cityId, String category, UUID driverId, LocationUpdate update);

    /** An update from the app; {@code replay} marks one recorded while the app was offline. */
    record Incoming(LocationUpdate update, boolean replay) {
    }

    /** {@code lastAppliedSeq} is null when nothing was applied. */
    record BatchResult(int applied, int stale, int ignored, Long lastAppliedSeq) {

        public static BatchResult allIgnored(int count) {
            return new BatchResult(0, 0, count, null);
        }
    }
}
