package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.db.AvailabilityRepository;
import com.ridehailing.dispatch.db.AvailabilityRepository.AvailabilityRow;
import com.ridehailing.dispatch.db.DriverStatsRepository;
import com.ridehailing.dispatch.db.DriverStatsRepository.Stat;
import com.ridehailing.dispatch.db.OfferRepository;
import com.ridehailing.dispatch.db.SearchTaskRepository;
import com.ridehailing.geography.GeographyApi;
import com.ridehailing.geography.GeographyApi.CategorySettings;
import com.ridehailing.ride.RideDispatchParticipant;
import com.ridehailing.shared.Actor;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Dispatch's side of ride transitions (LLD §2.3), in the ride's transaction, after the ride row is locked. */
@Component
class DispatchParticipant implements RideDispatchParticipant {

    static final String RIDE_END_ACTOR = "ride-end";
    private static final Logger log = LoggerFactory.getLogger(DispatchParticipant.class);

    private final GeographyApi geography;
    private final SearchTaskRepository tasks;
    private final OfferRepository offers;
    private final AvailabilityRepository availability;
    private final OfferEndings endings;
    private final LiveIndexMirror mirror;
    private final Availability drivers;
    private final DriverStatsRepository stats;

    DispatchParticipant(GeographyApi geography, SearchTaskRepository tasks, OfferRepository offers,
            AvailabilityRepository availability, OfferEndings endings, LiveIndexMirror mirror, Availability drivers,
            DriverStatsRepository stats) {
        this.geography = geography;
        this.tasks = tasks;
        this.offers = offers;
        this.availability = availability;
        this.endings = endings;
        this.mirror = mirror;
        this.drivers = drivers;
        this.stats = stats;
    }

    @Override
    public Duration searchStarted(SearchStarted search) {
        CategorySettings settings = geography.settings(search.cityId(), search.category()).orElseThrow(
                () -> new IllegalStateException("No dispatch settings for " + search.category() + " in "
                        + search.cityId()));
        tasks.start(search.rideId(), search.cityId(), search.category(), search.pickup(), search.priority(),
                settings.radiusStartM());
        return Duration.ofSeconds(settings.searchTimeoutS());
    }

    /** Lock order ride (the caller's), offer, availability (§6.1); the task goes without waiting (§6.2). */
    @Override
    public void searchStopped(UUID rideId, SearchStop reason) {
        offers.lockPendingForRide(rideId).ifPresent(offer -> {
            endings.withdraw(offer, reason.name());
            availability.release(offer.driverId(), offer.id(), null).ifPresent(mirror::afterCommit);
        });
        tasks.deleteWithoutWaiting(rideId);
    }

    /**
     * Unreachable drivers, and those suspended during the ride, go offline; everyone else becomes available. A driver
     * who isn't on the ride breaks I4, which reports it; the release then leaves them alone. A CHECK ties a ride to
     * {@code ASSIGNED} and {@code ON_TRIP}, so being on the ride is enough.
     */
    @Override
    public void driverReleased(UUID rideId, UUID driverId, DriverRelease reason) {
        AvailabilityRow locked = availability.lock(driverId).filter(row -> rideId.equals(row.rideId())).orElse(null);
        if (locked == null) {
            log.error("Ride {} released driver {}, who isn't on it: an invariant is broken", rideId, driverId);
            return;
        }
        if (reason == DriverRelease.DRIVER_CANCELLED) {
            stats.count(driverId, Stat.CANCELLED_AFTER_ACCEPT);
        }
        if (reason == DriverRelease.UNREACHABLE) {
            drivers.takeOffline(locked, "UNREACHABLE", Actor.system(Sweeper.SYSTEM_ACTOR));
        } else if (locked.offlineAfterRide()) {
            drivers.takeOffline(locked, "SUSPENDED", Actor.system(RIDE_END_ACTOR));
        } else {
            availability.releaseFromRide(driverId, rideId).ifPresent(mirror::afterCommit);
        }
    }

    @Override
    public void tripStarted(UUID rideId, UUID driverId) {
        availability.startTrip(driverId, rideId).ifPresentOrElse(mirror::afterCommit, () -> log.error(
                "Ride {} started with driver {}, who isn't assigned to it: an invariant is broken", rideId, driverId));
    }
}
