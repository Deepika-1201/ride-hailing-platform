package com.ridehailing.location;

import com.ridehailing.shared.GeoPoint;
import java.util.List;
import java.util.UUID;

/**
 * Where available drivers are (LLD §9.1). Phase 6 provides the in-memory implementation; until then no bean exists
 * and callers do without, as quotes do for the pickup ETA (LLD §10.4).
 */
public interface LiveIndex {

    /** Up to {@code k} available drivers of the category within the radius, heard from recently, nearest first. */
    List<Candidate> nearby(String cityId, String category, GeoPoint at, int radiusM, int k);

    record Candidate(UUID driverId, GeoPoint position, int distanceM) {
    }
}
