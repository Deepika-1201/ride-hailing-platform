package com.ridehailing.dispatch;

import java.time.Instant;
import java.util.UUID;

/** Driver availability for the API and the other modules (LLD §2.2); offers and acceptance arrive in phase 7. */
public interface DispatchApi {

    /**
     * Puts the driver online with the vehicle (LLD §8.2): {@code 409 DRIVER_NOT_ELIGIBLE} with the reason, or
     * {@code 409 INVALID_TRANSITION} if online with another vehicle. Online with this vehicle already answers the
     * current status. Joins the caller's transaction or starts one; the live index learns of it after commit.
     */
    DriverStatusView goOnline(UUID driverId, UUID vehicleId);

    /** Takes the driver offline: {@code 409 DRIVER_HAS_ACTIVE_RIDE} during a ride; offline already is no change. */
    DriverStatusView goOffline(UUID driverId);

    /** The driver's availability; a driver who never went online is {@code OFFLINE} at version 0. */
    DriverStatusView status(UUID driverId);

    /** The DriverStatus schema; null fields don't apply in the status. */
    record DriverStatusView(UUID driverId, AvailabilityStatus status, String cityId, UUID vehicleId, String category,
            Instant onlineSince, UUID offerId, UUID rideId, long version) {

        public static DriverStatusView neverOnline(UUID driverId) {
            return new DriverStatusView(driverId, AvailabilityStatus.OFFLINE, null, null, null, null, null, null, 0);
        }
    }
}
