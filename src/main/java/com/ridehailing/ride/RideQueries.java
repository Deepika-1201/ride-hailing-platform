package com.ridehailing.ride;

import java.util.Optional;
import java.util.UUID;

/** Reading rides, for every module (LLD §2.2). */
public interface RideQueries {

    /** The whole ride; callers show each party its own view. */
    Optional<RideView> find(UUID rideId);
}
