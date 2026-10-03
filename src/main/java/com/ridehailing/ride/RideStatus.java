package com.ridehailing.ride;

import java.util.Set;

/** A ride's state (ride lifecycle §2). */
public enum RideStatus {
    SEARCHING,
    DRIVER_ASSIGNED,
    DRIVER_ARRIVED,
    IN_TRIP,
    COMPLETED,
    CANCELLED_BY_RIDER,
    CANCELLED_BY_DRIVER,
    CANCELLED_BY_SYSTEM,
    DRIVER_NOT_FOUND;

    private static final Set<RideStatus> ACTIVE = Set.of(SEARCHING, DRIVER_ASSIGNED, DRIVER_ARRIVED, IN_TRIP);

    public boolean active() {
        return ACTIVE.contains(this);
    }
}
