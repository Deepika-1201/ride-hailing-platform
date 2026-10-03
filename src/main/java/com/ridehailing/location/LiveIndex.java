package com.ridehailing.location;

import com.ridehailing.shared.GeoPoint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where online drivers are and whether they may be matched (LLD §9.1). Positions come from location updates; the
 * status comes only from dispatch's mirror writes, never from the driver's app.
 */
public interface LiveIndex {

    /** Applies one update if its sequence number is newer, for a driver mirrored online in the category. */
    UpdateResult update(String cityId, UUID driverId, String category, LocationUpdate update);

    /** Up to {@code k} available drivers of the category within the radius, heard from recently, nearest first. */
    List<Candidate> nearby(String cityId, String category, GeoPoint at, int radiusM, int k);

    Optional<LivePosition> position(String cityId, UUID driverId);

    /** Applies dispatch's state if its version is newer than what the index holds; returns whether it was. */
    boolean mirror(String cityId, UUID driverId, MirrorState state);

    /** Drivers last heard from in the minute before the cutoff leave matching; returns them. */
    List<UUID> sweep(String cityId, Instant silentBefore);

    /** When each of the drivers was last heard from; drivers never heard from are absent. */
    Map<UUID, Instant> lastSeen(String cityId, Collection<UUID> drivers);

    /** When the index started holding this city's data: before then, silence means nothing. */
    Instant epoch(String cityId);

    /** Every driver whose state the index holds, offline tombstones included, for the reconciler. */
    Map<UUID, MirrorState> mirrored(String cityId);

    enum Status {
        OFFLINE,
        AVAILABLE,
        OFFERED,
        ASSIGNED,
        ON_TRIP
    }

    /** {@code headingDeg} and {@code speedMps} may be null. */
    record LocationUpdate(long seq, GeoPoint position, double accuracyM, Double headingDeg, Double speedMps,
            Instant deviceTime) {
    }

    /** {@code category} and {@code rideId} may be null. */
    record MirrorState(Status status, long version, String category, UUID rideId) {
    }

    /** {@code status} and {@code rideId} are the mirror's; {@code category} is the mirror's on a mismatch only. */
    record UpdateResult(Outcome outcome, Status status, String category, UUID rideId) {

        public enum Outcome {
            APPLIED,
            STALE,
            OFFLINE,
            CATEGORY_MISMATCH
        }
    }

    record Candidate(UUID driverId, GeoPoint position, int distanceM, Instant lastSeen) {
    }

    record LivePosition(GeoPoint position, Instant receivedAt, long seq) {
    }
}
