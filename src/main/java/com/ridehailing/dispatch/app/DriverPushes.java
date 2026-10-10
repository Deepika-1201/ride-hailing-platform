package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.AvailabilityStatus;
import com.ridehailing.dispatch.db.AvailabilityRepository.AvailabilityRow;
import com.ridehailing.dispatch.db.OfferRepository.OfferRow;
import com.ridehailing.platform.PushBus;
import com.ridehailing.platform.Transactions;
import com.ridehailing.rating.RatingApi.RatingSummary;
import com.ridehailing.ride.RideAssignment.SearchingRide;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Dispatch's pushes to drivers (LLD §14.7), each sent once the caller's transaction commits. */
@Component
class DriverPushes {

    private final PushBus push;
    private final Transactions transactions;

    DriverPushes(PushBus push, Transactions transactions) {
        this.push = push;
        this.transactions = transactions;
    }

    /**
     * {@code expires_in_ms} is counted when the push goes: the time to live the offer was just inserted with, by the
     * database clock, less the time since by this process's monotonic clock, so the two clocks needn't agree.
     */
    void offer(OfferRow offer, SearchingRide ride, Duration ttl) {
        long madeAt = System.nanoTime();
        Person rider = ride.rider() == null ? null : new Person(ride.rider().firstName(), ride.rider().rating());
        transactions.afterCommit(() -> push.publish(PushBus.driverChannel(offer.driverId()), new OfferMessage(
                offer.id(), offer.rideId(), ride.category(), ride.pickup(), ride.dropoff(), offer.distanceM(),
                ride.fare(), rider, offer.expiresAt(),
                Math.max(0, ttl.toMillis() - (System.nanoTime() - madeAt) / 1_000_000))));
    }

    /** The offer ended without the driver's answer. */
    void offerWithdrawn(OfferRow offer, String reason) {
        transactions.afterCommit(() -> push.publish(PushBus.driverChannel(offer.driverId()),
                new OfferWithdrawnMessage(offer.id(), reason)));
    }

    /** An availability change the server made, such as taking the driver offline. */
    void statusChanged(AvailabilityRow row, String reason) {
        transactions.afterCommit(() -> push.publish(PushBus.driverChannel(row.driverId()),
                new DriverStatusMessage(row.status(), row.version(), row.category(), reason)));
    }

    /** The messages of {@code server-messages.v1.json}. */
    record OfferMessage(String type, UUID offerId, UUID rideId, String category, GeoPoint pickup, GeoPoint dropoff,
            int pickupDistanceM, Money fare, Person rider, Instant expiresAt, long expiresInMs) {

        OfferMessage(UUID offerId, UUID rideId, String category, GeoPoint pickup, GeoPoint dropoff,
                int pickupDistanceM, Money fare, Person rider, Instant expiresAt, long expiresInMs) {
            this("offer", offerId, rideId, category, pickup, dropoff, pickupDistanceM, fare, rider, expiresAt,
                    expiresInMs);
        }
    }

    record OfferWithdrawnMessage(String type, UUID offerId, String reason) {

        OfferWithdrawnMessage(UUID offerId, String reason) {
            this("offer_withdrawn", offerId, reason);
        }
    }

    record DriverStatusMessage(String type, AvailabilityStatus status, long version, String category, String reason) {

        DriverStatusMessage(AvailabilityStatus status, long version, String category, String reason) {
            this("driver_status", status, version, category, reason);
        }
    }

    record Person(String firstName, RatingSummary rating) {
    }
}
