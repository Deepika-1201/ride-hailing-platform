package com.ridehailing.location;

import com.ridehailing.location.LiveIndex.LocationUpdate;
import java.util.List;
import java.util.UUID;

/** Location updates from drivers' apps (LLD §9.6): REST in V1, the WebSocket from V2. */
public interface LocationIngestion {

    /**
     * Applies the updates in sequence order for a driver online in the city with the category. Invalid and
     * out-of-bounds updates are ignored and counted.
     */
    BatchResult accept(String cityId, String category, UUID driverId, List<LocationUpdate> updates);

    /** {@code lastAppliedSeq} is null when nothing was applied. */
    record BatchResult(int applied, int stale, int ignored, Long lastAppliedSeq) {

        public static BatchResult allIgnored(int count) {
            return new BatchResult(0, 0, count, null);
        }
    }
}
