package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.dispatch.db.AvailabilityRepository;
import com.ridehailing.dispatch.db.OfferRepository;
import com.ridehailing.dispatch.db.SearchTaskRepository;
import com.ridehailing.platform.Timers;
import com.ridehailing.ride.RideView;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class DispatchService implements DispatchApi {

    static final Duration RECENT_OFFERS = Duration.ofDays(1);

    private final Availability availability;
    private final Offers offers;
    private final AvailabilityRepository availabilityRows;
    private final OfferRepository offerRows;
    private final SearchTaskRepository tasks;
    private final Timers timers;

    DispatchService(Availability availability, Offers offers, AvailabilityRepository availabilityRows,
            OfferRepository offerRows, SearchTaskRepository tasks, Timers timers) {
        this.availability = availability;
        this.offers = offers;
        this.availabilityRows = availabilityRows;
        this.offerRows = offerRows;
        this.tasks = tasks;
        this.timers = timers;
    }

    @Override
    public DriverStatusView goOnline(UUID driverId, UUID vehicleId) {
        return availability.goOnline(driverId, vehicleId);
    }

    @Override
    public DriverStatusView goOffline(UUID driverId) {
        return availability.goOffline(driverId);
    }

    @Override
    public DriverStatusView status(UUID driverId) {
        return availability.status(driverId);
    }

    @Override
    public Optional<OfferView> currentOffer(UUID driverId) {
        return offers.current(driverId);
    }

    @Override
    public RideView accept(UUID offerId, UUID driverId) {
        return offers.accept(offerId, driverId);
    }

    @Override
    public OfferView decline(UUID offerId, UUID driverId) {
        return offers.decline(offerId, driverId);
    }

    @Override
    public List<BusyDriver> busyDrivers(String cityId) {
        return availabilityRows.busy(cityId).stream()
                .map(row -> new BusyDriver(row.driverId(), row.status(), row.rideId()))
                .toList();
    }

    @Override
    public Map<UUID, String> dispatchWork(String cityId) {
        Map<UUID, String> work = new HashMap<>();
        offerRows.ridesWithPendingOffers(cityId).forEach(ride -> work.put(ride, "a pending offer"));
        tasks.ridesWithTasks(cityId).forEach(ride -> work.putIfAbsent(ride, "a search task"));
        Map<UUID, UUID> recent = offerRows.recentOffers(cityId, RECENT_OFFERS);
        timers.scheduled(DispatchTimers.OFFER_EXPIRY, recent.keySet())
                .forEach(offer -> work.putIfAbsent(recent.get(offer), "an offer timer"));
        return work;
    }
}
