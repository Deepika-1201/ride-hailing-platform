package com.ridehailing.ride;

import com.ridehailing.platform.Cursor;
import com.ridehailing.shared.Page;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Reading rides, for every module (LLD §2.2). */
public interface RideQueries {

    /** The whole ride; callers show each party its own view. */
    Optional<RideView> find(UUID rideId);

    /** Rides with a driver now, in the city or in all when {@code cityId} is null (I4). */
    List<DrivenRide> drivenRides(String cityId);

    /** The current status of each of the rides that exist. */
    Map<UUID, RideStatus> statuses(Collection<UUID> rideIds);

    /** Rides of the city that ended within the last day with their search timer still scheduled (I6). */
    List<UUID> endedWithSearchTimer(String cityId);

    /** Operations' list (LLD §13.5), newest first after the cursor; views show everything, callers pick one. */
    Page<RideView> list(RideFilter filter, Cursor after, int limit);

    /** The ride's transitions, oldest first; empty for a ride that doesn't exist. */
    List<TransitionView> transitions(UUID rideId);

    record DrivenRide(UUID driverId, UUID rideId, RideStatus status) {
    }

    /** No statuses means any; a null city means every city. */
    record RideFilter(Set<RideStatus> statuses, String cityId) {

        public RideFilter {
            statuses = Set.copyOf(statuses);
        }
    }

    /** One row of the transition log (LLD §4.5); {@code fromStatus} is null for the booking. */
    record TransitionView(int version, String fromStatus, String toStatus, String command, String actorType,
            String actorId, String reason, Instant occurredAt, String requestId) {
    }
}
