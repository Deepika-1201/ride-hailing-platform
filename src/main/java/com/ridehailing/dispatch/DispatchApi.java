package com.ridehailing.dispatch;

import com.ridehailing.ride.RideView;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Driver availability and offers for the API and the other modules (LLD §2.2). */
public interface DispatchApi {

    /**
     * Puts the driver online with the vehicle (LLD §8.2): {@code 409 DRIVER_NOT_ELIGIBLE} with the reason, or
     * {@code 409 INVALID_TRANSITION} if online with another vehicle. Online with this vehicle already answers the
     * current status. Joins the caller's transaction or starts one; the live index learns of it after commit.
     */
    DriverStatusView goOnline(UUID driverId, UUID vehicleId);

    /**
     * Takes the driver offline, declining a pending offer first: {@code 409 DRIVER_HAS_ACTIVE_RIDE} during a ride;
     * offline already is no change.
     */
    DriverStatusView goOffline(UUID driverId);

    /** The driver's availability; a driver who never went online is {@code OFFLINE} at version 0. */
    DriverStatusView status(UUID driverId);

    /** The driver's pending offer, which this marks as seen (§8.5). */
    Optional<OfferView> currentOffer(UUID driverId);

    /** The {@code offer_seen} message (§8.5): marks the driver's offer seen if it is still pending. */
    void offerSeen(UUID driverId, UUID offerId);

    /** T2 (§8.4): the driver's view of the ride; {@code 409 OFFER_NO_LONGER_AVAILABLE} if too late. */
    RideView accept(UUID offerId, UUID driverId);

    /** §8.7: declining again is no change; {@code 409 OFFER_NO_LONGER_AVAILABLE} if the offer ended otherwise. */
    OfferView decline(UUID offerId, UUID driverId);

    /**
     * Acts on the driver's suspension in the caller's transaction, which it requires (LLD §8.8): withdraws a pending
     * offer, takes an online driver offline, and marks a driver on a ride to go offline when it ends. {@code 409
     * INVALID_TRANSITION} if an offer reached the driver between its read and its lock.
     */
    void driverSuspended(UUID driverId, Actor ops);

    /** A driver reinstated during the ride they were suspended in stays online after it (LLD §8.8). */
    void driverReinstated(UUID driverId);

    /** Drivers {@code ASSIGNED} or {@code ON_TRIP}, in the city or in all when {@code cityId} is null (I4). */
    List<BusyDriver> busyDrivers(String cityId);

    /**
     * What dispatch still holds for rides of the city: a pending offer, a search task, or an offer timer from the last
     * day; ride ID to what (I6).
     */
    Map<UUID, String> dispatchWork(String cityId);

    record BusyDriver(UUID driverId, AvailabilityStatus status, UUID rideId) {
    }

    /** The DriverStatus schema; null fields don't apply in the status. */
    record DriverStatusView(UUID driverId, AvailabilityStatus status, String cityId, UUID vehicleId, String category,
            Instant onlineSince, UUID offerId, UUID rideId, long version) {

        public static DriverStatusView neverOnline(UUID driverId) {
            return new DriverStatusView(driverId, AvailabilityStatus.OFFLINE, null, null, null, null, null, null, 0);
        }
    }

    /** The Offer schema; {@code rider} is null for a rider without a first name, {@code expiresInMs} unless pending. */
    record OfferView(UUID id, UUID rideId, OfferStatus status, String category, GeoPoint pickup, GeoPoint dropoff,
            int pickupDistanceM, Money fare, RideView.PersonSummary rider, Instant createdAt, Instant expiresAt,
            Long expiresInMs) {
    }

    enum OfferStatus {
        PENDING,
        ACCEPTED,
        DECLINED,
        EXPIRED,
        WITHDRAWN
    }
}
