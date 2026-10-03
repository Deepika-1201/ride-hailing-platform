package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.DispatchApi.OfferStatus;
import com.ridehailing.dispatch.db.DriverStatsRepository;
import com.ridehailing.dispatch.db.DriverStatsRepository.Stat;
import com.ridehailing.dispatch.db.OfferRepository;
import com.ridehailing.dispatch.db.OfferRepository.OfferRow;
import com.ridehailing.dispatch.db.SearchTaskRepository;
import com.ridehailing.dispatch.events.OfferDeclined;
import com.ridehailing.dispatch.events.OfferExpired;
import com.ridehailing.dispatch.events.OfferWithdrawn;
import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.Timers;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Ends a pending offer that the caller holds locked (LLD §8.6, §8.7, §7.3): the offer row, its timer, the ride's
 * task, the event and the driver's counts. What becomes of the driver's availability is the caller's decision.
 */
@Component
class OfferEndings {

    private final OfferRepository offers;
    private final SearchTaskRepository tasks;
    private final Timers timers;
    private final Outbox outbox;
    private final DriverStatsRepository stats;
    private final DispatchMetrics metrics;
    private final Clock clock;

    OfferEndings(OfferRepository offers, SearchTaskRepository tasks, Timers timers, Outbox outbox,
            DriverStatsRepository stats, DispatchMetrics metrics, Clock clock) {
        this.offers = offers;
        this.tasks = tasks;
        this.timers = timers;
        this.outbox = outbox;
        this.stats = stats;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** By the driver ({@code DRIVER}) or by going offline ({@code DRIVER_OFFLINE}); the search goes on at once. */
    OfferRow decline(OfferRow offer, String reason) {
        OfferRow declined = offers.end(offer.id(), OfferStatus.DECLINED, reason, true);
        tasks.makeDue(offer.rideId());
        timers.cancel(DispatchTimers.OFFER_EXPIRY, offer.id());
        outbox.append(event(OfferDeclined.TYPE, OfferDeclined.VERSION, declined, new OfferDeclined(offer.id(),
                offer.rideId(), offer.driverId(), reason, declined.respondedAt())));
        stats.count(offer.driverId(), Stat.DECLINED);
        metrics.ended(OfferStatus.DECLINED);
        return declined;
    }

    /** By its timer, whose transaction deletes the timer; the search goes on at once. */
    OfferRow expire(OfferRow offer) {
        OfferRow expired = offers.end(offer.id(), OfferStatus.EXPIRED, null, false);
        tasks.makeDue(offer.rideId());
        outbox.append(event(OfferExpired.TYPE, OfferExpired.VERSION, expired, new OfferExpired(offer.id(),
                offer.rideId(), offer.driverId(), offer.seenAt() != null, clock.instant())));
        stats.count(offer.driverId(), Stat.EXPIRED);
        metrics.ended(OfferStatus.EXPIRED);
        return expired;
    }

    /** Because the ride stopped searching; the caller removes the ride's task. */
    OfferRow withdraw(OfferRow offer, String reason) {
        OfferRow withdrawn = offers.end(offer.id(), OfferStatus.WITHDRAWN, reason, false);
        timers.cancel(DispatchTimers.OFFER_EXPIRY, offer.id());
        outbox.append(event(OfferWithdrawn.TYPE, OfferWithdrawn.VERSION, withdrawn, new OfferWithdrawn(offer.id(),
                offer.rideId(), offer.driverId(), reason, clock.instant())));
        metrics.ended(OfferStatus.WITHDRAWN);
        return withdrawn;
    }

    /** Offer events belong to the offer and are ordered with the ride's events. */
    static DomainEvent event(String type, int version, OfferRow offer, Object payload) {
        return new DomainEvent(type, version, "offer", offer.id(), offer.version(), offer.rideId(), payload);
    }
}
