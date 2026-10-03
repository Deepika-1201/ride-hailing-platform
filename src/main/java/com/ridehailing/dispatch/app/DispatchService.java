package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.ride.RideView;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class DispatchService implements DispatchApi {

    private final Availability availability;
    private final Offers offers;

    DispatchService(Availability availability, Offers offers) {
        this.availability = availability;
        this.offers = offers;
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
}
