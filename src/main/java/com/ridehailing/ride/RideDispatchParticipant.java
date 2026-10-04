package com.ridehailing.ride;

import com.ridehailing.shared.GeoPoint;
import java.time.Duration;
import java.util.UUID;

/**
 * Dispatch's part in ride transitions, declared by ride and implemented by dispatch (LLD §2.3). Each method runs in
 * the ride's transaction, after the ride row is updated.
 */
public interface RideDispatchParticipant {

    /** Starts searching for a driver; answers how long the search may last, from the city's settings. */
    Duration searchStarted(SearchStarted search);

    /** Ends the search: withdraws a pending offer, releases its driver, and removes the task and offer timer. */
    void searchStopped(UUID rideId, SearchStop reason);

    /** The ride let its driver go (T6–T8, T10–T13); dispatch decides whether they become available or offline. */
    void driverReleased(UUID rideId, UUID driverId, DriverRelease reason);

    /** T9: the driver's availability goes {@code ASSIGNED → ON_TRIP}. */
    void tripStarted(UUID rideId, UUID driverId);

    /** {@code priority} is 1 for a ride whose driver was released (T6, T7), else 0. */
    record SearchStarted(UUID rideId, String cityId, String category, GeoPoint pickup, int priority) {
    }

    /** Why a pending offer is withdrawn (the OfferWithdrawn reasons). */
    enum SearchStop {
        RIDER_CANCELLED,
        SEARCH_TIMEOUT,
        OPS_CANCELLED
    }

    /** Why the ride let its driver go (LLD §2.3). */
    enum DriverRelease {
        RIDER_CANCELLED,
        DRIVER_CANCELLED,
        NO_SHOW,
        COMPLETED,
        OPS_CANCELLED,
        UNREACHABLE
    }
}
