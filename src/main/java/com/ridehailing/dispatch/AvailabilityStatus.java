package com.ridehailing.dispatch;

/** A driver's availability (LLD §8.1); the live index mirrors it. */
public enum AvailabilityStatus {
    OFFLINE,
    AVAILABLE,
    OFFERED,
    ASSIGNED,
    ON_TRIP
}
