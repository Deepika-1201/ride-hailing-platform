package com.ridehailing.location;

import com.ridehailing.shared.BoundingBox;
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

    /**
     * Applies one update if its sequence number is newer, for a driver mirrored online in the category; a flagged one
     * (§9.5) is a sign of life that doesn't move the position.
     */
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

    /** When the index began holding this city's data (§8.10); now, the youngest it can be, if it hasn't begun. */
    Instant epoch(String cityId);

    /**
     * Sets the city's epoch to now unless the index has one, and answers whether it did: then the index never held the
     * city's data or lost it, and the reconciler repairs the city at once (§8.10).
     */
    boolean beginEpoch(String cityId);

    /** Every driver whose state the index holds, offline tombstones included, for the reconciler. */
    Map<UUID, MirrorState> mirrored(String cityId);

    /** Up to {@code max} online drivers with a position inside the box, nearest its centre first (operations map). */
    List<OnlineDriver> snapshot(String cityId, BoundingBox box, int max);

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
    record UpdateResult(Outcome outcome, Status status, String category, UUID rideId, int flags) {

        /** Worse than the accuracy limit: a sign of life, not a position (§9.5). */
        public static final int POOR_ACCURACY = 1;
        /** Faster than the speed limit from the last usable position: not used (§9.5). */
        public static final int IMPLAUSIBLE = 2;

        public UpdateResult(Outcome outcome, Status status, String category, UUID rideId) {
            this(outcome, status, category, rideId, 0);
        }

        public enum Outcome {
            APPLIED,
            STALE,
            OFFLINE,
            CATEGORY_MISMATCH
        }
    }

    /**
     * The quality rules (§9.5): accuracy and speed limits, and the implausible update, counted in a row, that is taken
     * as the new position after all.
     */
    record Quality(double maxAccuracyM, double maxSpeedMps, int reanchorAfter) {

        public static final Quality DEFAULT = new Quality(100, 150 / 3.6, 3);
    }

    record Candidate(UUID driverId, GeoPoint position, int distanceM, Instant lastSeen) {
    }

    /** The last usable position and when it arrived; {@code seq} is the last applied update's. */
    record LivePosition(GeoPoint position, Instant receivedAt, long seq) {
    }

    /** {@code rideId} may be null; {@code lastSeen} is when the driver was last heard from. */
    record OnlineDriver(UUID driverId, GeoPoint position, Status status, String category, UUID rideId,
            Instant lastSeen) {
    }
}
