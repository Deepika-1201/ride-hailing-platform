package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.AvailabilityStatus;
import com.ridehailing.dispatch.DispatchApi.OfferStatus;
import com.ridehailing.dispatch.db.AvailabilityRepository;
import com.ridehailing.dispatch.db.AvailabilityRepository.AvailabilityRow;
import com.ridehailing.dispatch.db.OfferRepository;
import com.ridehailing.dispatch.db.OfferRepository.OfferRow;
import com.ridehailing.platform.DueTimer;
import com.ridehailing.platform.TimerHandler;
import com.ridehailing.platform.TimerKind;
import com.ridehailing.shared.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * §8.6, in the timer's transaction; lock order offer, availability (§6.1). Only offers the driver saw count towards
 * going offline: an unseen one says nothing about the driver.
 */
@Component
class OfferExpiryHandler implements TimerHandler {

    static final String UNRESPONSIVE = "UNRESPONSIVE";
    private static final Logger log = LoggerFactory.getLogger(OfferExpiryHandler.class);

    private final OfferRepository offers;
    private final AvailabilityRepository availability;
    private final OfferEndings endings;
    private final Availability drivers;
    private final LiveIndexMirror mirror;
    private final DispatchProperties properties;

    OfferExpiryHandler(OfferRepository offers, AvailabilityRepository availability, OfferEndings endings,
            Availability drivers, LiveIndexMirror mirror, DispatchProperties properties) {
        this.offers = offers;
        this.availability = availability;
        this.endings = endings;
        this.drivers = drivers;
        this.mirror = mirror;
        this.properties = properties;
    }

    @Override
    public TimerKind kind() {
        return DispatchTimers.OFFER_EXPIRY;
    }

    @Override
    public void fire(DueTimer timer) {
        OfferRow offer = offers.lock(timer.aggregateId()).orElse(null);
        if (offer == null || offer.status() != OfferStatus.PENDING) {
            return;
        }
        endings.expire(offer);
        AvailabilityRow driver = availability.lock(offer.driverId()).orElseThrow();
        if (driver.status() != AvailabilityStatus.OFFERED || !offer.id().equals(driver.offerId())) {
            log.error("Offer {} expired but driver {} doesn't hold it: an invariant is broken", offer.id(),
                    offer.driverId());
            return;
        }
        int expiredInARow = driver.consecutiveExpired() + (offer.seenAt() != null ? 1 : 0);
        if (expiredInARow >= properties.maxConsecutiveExpired()) {
            drivers.takeOffline(driver, UNRESPONSIVE, Actor.system("offer-expiry"));
            return;
        }
        availability.release(driver.driverId(), offer.id(), expiredInARow).ifPresent(mirror::afterCommit);
    }
}
