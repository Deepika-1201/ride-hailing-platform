package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.db.AvailabilityRepository;
import com.ridehailing.dispatch.db.OfferRepository;
import com.ridehailing.dispatch.db.SearchTaskRepository;
import com.ridehailing.geography.GeographyApi;
import com.ridehailing.geography.GeographyApi.CategorySettings;
import com.ridehailing.ride.RideDispatchParticipant;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Dispatch's side of ride transitions (LLD §2.3), in the ride's transaction, after the ride row is locked. */
@Component
class DispatchParticipant implements RideDispatchParticipant {

    private final GeographyApi geography;
    private final SearchTaskRepository tasks;
    private final OfferRepository offers;
    private final AvailabilityRepository availability;
    private final OfferEndings endings;
    private final LiveIndexMirror mirror;

    DispatchParticipant(GeographyApi geography, SearchTaskRepository tasks, OfferRepository offers,
            AvailabilityRepository availability, OfferEndings endings, LiveIndexMirror mirror) {
        this.geography = geography;
        this.tasks = tasks;
        this.offers = offers;
        this.availability = availability;
        this.endings = endings;
        this.mirror = mirror;
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
}
