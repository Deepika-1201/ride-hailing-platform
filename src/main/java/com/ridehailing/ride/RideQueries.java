package com.ridehailing.ride;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    record DrivenRide(UUID driverId, UUID rideId, RideStatus status) {
    }
}
