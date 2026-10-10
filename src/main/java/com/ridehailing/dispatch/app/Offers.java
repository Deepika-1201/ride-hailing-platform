package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.DispatchApi.OfferStatus;
import com.ridehailing.dispatch.DispatchApi.OfferView;
import com.ridehailing.dispatch.db.AvailabilityRepository;
import com.ridehailing.dispatch.db.AvailabilityRepository.AvailabilityRow;
import com.ridehailing.dispatch.db.DriverStatsRepository;
import com.ridehailing.dispatch.db.DriverStatsRepository.Stat;
import com.ridehailing.dispatch.db.OfferRepository;
import com.ridehailing.dispatch.db.OfferRepository.OfferRow;
import com.ridehailing.dispatch.db.SearchTaskRepository;
import com.ridehailing.dispatch.events.OfferAccepted;
import com.ridehailing.driver.DriverApi;
import com.ridehailing.driver.DriverApi.DriverSnapshot;
import com.ridehailing.driver.DriverApi.Vehicle;
import com.ridehailing.geography.GeographyApi;
import com.ridehailing.geography.GeographyApi.CityView;
import com.ridehailing.geography.RoutingProvider;
import com.ridehailing.geography.RoutingProvider.Route;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.Timers;
import com.ridehailing.platform.Transactions;
import com.ridehailing.rating.RatingApi;
import com.ridehailing.rating.RatingApi.Party;
import com.ridehailing.ride.RideAssignment;
import com.ridehailing.ride.RideAssignment.AssignDriver;
import com.ridehailing.ride.RideAssignment.AssignedDriver;
import com.ridehailing.ride.RideAssignment.Assignment;
import com.ridehailing.ride.RideQueries;
import com.ridehailing.ride.RideView;
import com.ridehailing.shared.GeoPoint;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** A driver's offers: seeing (§8.5), accepting (T2, §8.4) and declining (§8.7). */
@Service
class Offers {

    /** The mock router's detour and slowest speed, for a driver without a live position (ADR-013). */
    static final double DETOUR_FACTOR = 1.35;
    static final double FALLBACK_SPEED_MPS = 18_000.0 / 3_600;
    private static final Logger log = LoggerFactory.getLogger(Offers.class);

    private final OfferRepository offers;
    private final AvailabilityRepository availability;
    private final SearchTaskRepository tasks;
    private final DriverStatsRepository stats;
    private final OfferEndings endings;
    private final RideAssignment assignment;
    private final RideQueries rides;
    private final DriverApi drivers;
    private final RatingApi ratings;
    private final GeographyApi geography;
    private final RoutingProvider routing;
    private final LiveIndex index;
    private final Timers timers;
    private final Outbox outbox;
    private final LiveIndexMirror mirror;
    private final Transactions transactions;
    private final DispatchMetrics metrics;
    private final Clock clock;

    Offers(OfferRepository offers, AvailabilityRepository availability, SearchTaskRepository tasks,
            DriverStatsRepository stats, OfferEndings endings, RideAssignment assignment, RideQueries rides,
            DriverApi drivers, RatingApi ratings, GeographyApi geography, RoutingProvider routing, LiveIndex index,
            Timers timers, Outbox outbox, LiveIndexMirror mirror, Transactions transactions, DispatchMetrics metrics,
            Clock clock) {
        this.offers = offers;
        this.availability = availability;
        this.tasks = tasks;
        this.stats = stats;
        this.endings = endings;
        this.assignment = assignment;
        this.rides = rides;
        this.drivers = drivers;
        this.ratings = ratings;
        this.geography = geography;
        this.routing = routing;
        this.index = index;
        this.timers = timers;
        this.outbox = outbox;
        this.mirror = mirror;
        this.transactions = transactions;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** The first read marks the offer seen; the countdown comes from the database clock. */
    Optional<OfferView> current(UUID driverId) {
        return transactions.execute(() -> offers.pendingFor(driverId).map(offer -> {
            offers.markSeen(offer.id(), driverId);
            return view(offer, offers.expiresInMs(offer.id()));
        }));
    }

    /** The app displayed the offer (§8.5); someone else's offer, or one that ended, stays as it is. */
    void seen(UUID driverId, UUID offerId) {
        offers.markSeen(offerId, driverId);
    }

    /**
     * The ETA and the driver's snapshot are worked out first, without locks; then the ride, the offer and the
     * availability row are locked in that order (§6.1). A late offer rolls the whole transaction back.
     */
    RideView accept(UUID offerId, UUID driverId) {
        OfferRow offer = offers.find(offerId).filter(found -> found.driverId().equals(driverId))
                .orElseThrow(ApiException::notFound);
        UUID vehicleId = availability.find(driverId).map(AvailabilityRow::vehicleId).orElse(null);
        RideView ride = rides.find(offer.rideId()).orElseThrow();
        DriverSnapshot driver = vehicleId == null ? null : drivers.snapshot(driverId, vehicleId).orElse(null);
        if (driver == null) {
            throw noLongerAvailable();
        }
        int eta = promisedPickupEta(ride.cityId(), driverId, ride.pickup(), offer.distanceM());
        Vehicle vehicle = driver.vehicle();
        AssignedDriver assigned = new AssignedDriver(driver.firstName(), new RideView.VehicleSummary(vehicle.id(),
                vehicle.category(), vehicle.make(), vehicle.model(), vehicle.colour(), vehicle.plate()),
                ratings.summary(driverId, Party.DRIVER));
        return transactions.execute(() -> {
            Assignment result = assignment.assign(new AssignDriver(offer.rideId(), offerId, driverId, eta, assigned));
            if (result.repeated()) {
                return result.ride().forDriver();
            }
            OfferRow accepted = offers.accept(offerId, driverId).orElseThrow(Offers::noLongerAvailable);
            AvailabilityRow row = availability.assign(driverId, offerId, offer.rideId()).orElseThrow(() -> {
                log.error("Driver {} accepted offer {} but doesn't hold it: an invariant is broken", driverId, offerId);
                return noLongerAvailable();
            });
            tasks.deleteWithoutWaiting(offer.rideId());
            timers.cancel(DispatchTimers.OFFER_EXPIRY, offerId);
            outbox.append(OfferEndings.event(OfferAccepted.TYPE, OfferAccepted.VERSION, accepted, new OfferAccepted(
                    offerId, offer.rideId(), driverId, accepted.respondedAt())));
            stats.count(driverId, Stat.ACCEPTED);
            metrics.ended(OfferStatus.ACCEPTED);
            mirror.afterCommit(row);
            return result.ride().forDriver();
        });
    }

    /** Lock order offer, availability (§6.1). */
    OfferView decline(UUID offerId, UUID driverId) {
        return transactions.execute(() -> {
            OfferRow offer = offers.lock(offerId).filter(found -> found.driverId().equals(driverId))
                    .orElseThrow(ApiException::notFound);
            if (offer.status() == OfferStatus.DECLINED) {
                return view(offer, null);
            }
            if (offer.status() != OfferStatus.PENDING) {
                throw noLongerAvailable();
            }
            OfferRow declined = endings.decline(offer, "DRIVER");
            availability.release(driverId, offerId, 0).ifPresent(mirror::afterCommit);
            return view(declined, null);
        });
    }

    /** By road from the live position; without one, the offer's straight-line distance with the mock's detour. */
    int promisedPickupEta(String cityId, UUID driverId, GeoPoint pickup, int offerDistanceM) {
        ZonedDateTime departure = ZonedDateTime.ofInstant(clock.instant(),
                geography.city(cityId).map(CityView::timeZone).orElse(ZoneOffset.UTC));
        return index.position(cityId, driverId)
                .flatMap(position -> routing.route(position.position(), pickup, departure))
                .map(Route::durationS)
                .orElseGet(() -> (int) Math.ceil(offerDistanceM * DETOUR_FACTOR / FALLBACK_SPEED_MPS));
    }

    private OfferView view(OfferRow offer, Long expiresInMs) {
        RideView ride = rides.find(offer.rideId()).orElseThrow();
        return new OfferView(offer.id(), offer.rideId(), offer.status(), ride.category(), ride.pickup(),
                ride.dropoff(), offer.distanceM(), ride.fare(), ride.rider(), offer.createdAt(), offer.expiresAt(),
                expiresInMs);
    }

    static ApiException noLongerAvailable() {
        return new ApiException(HttpStatus.CONFLICT, "OFFER_NO_LONGER_AVAILABLE",
                "The offer expired, was withdrawn, or its ride moved on.");
    }
}
