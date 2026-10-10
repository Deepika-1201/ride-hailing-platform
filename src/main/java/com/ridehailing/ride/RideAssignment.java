package com.ridehailing.ride;

import com.ridehailing.rating.RatingApi.RatingSummary;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Money;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The ride's side of dispatch, for dispatch only (LLD §2.2); both methods join the caller's transaction. */
public interface RideAssignment {

    /** Holds the ride {@code FOR SHARE} if it is searching, so a cancellation waits for the attempt (§6.4). */
    Optional<SearchingRide> lockIfSearching(UUID rideId);

    /**
     * T2's ride part, first in the acceptance transaction (§8.4): locks the ride and assigns the driver, or answers
     * the ride already assigned through this offer. {@code 409 OFFER_NO_LONGER_AVAILABLE} if it stopped searching.
     */
    Assignment assign(AssignDriver command);

    /**
     * T7, for the sweeper (LLD §7.9): returns the ride to the search and takes the driver offline, if the ride is
     * still {@code DRIVER_ASSIGNED} to them; answers whether it did.
     */
    boolean unassignUnreachable(UUID rideId, UUID driverId);

    /** {@code requestedAt} is the database's time of booking; {@code rider} is null without a first name. */
    record SearchingRide(UUID rideId, String cityId, String category, GeoPoint pickup, GeoPoint dropoff, Money fare,
            RideView.PersonSummary rider, Instant requestedAt) {
    }

    record AssignDriver(UUID rideId, UUID offerId, UUID driverId, int promisedPickupEtaS, AssignedDriver driver) {
    }

    /** What the ride keeps of its driver: {@code firstName}, the vehicle and the driver's rating at assignment. */
    record AssignedDriver(String firstName, RideView.VehicleSummary vehicle, RatingSummary rating) {
    }

    /** {@code repeated} when this offer had already assigned the driver. */
    record Assignment(RideView ride, boolean repeated) {
    }
}
