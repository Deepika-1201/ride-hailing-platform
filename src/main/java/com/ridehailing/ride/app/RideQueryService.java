package com.ridehailing.ride.app;

import com.ridehailing.platform.InvariantCheck;
import com.ridehailing.ride.RideQueries;
import com.ridehailing.ride.RideView;
import com.ridehailing.ride.db.RideRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class RideQueryService implements RideQueries, InvariantCheck {

    private final RideRepository rides;
    private final RideViews views;

    RideQueryService(RideRepository rides, RideViews views) {
        this.rides = rides;
        this.views = views;
    }

    @Override
    public Optional<RideView> find(UUID rideId) {
        return rides.find(rideId).map(views::of);
    }

    /** I1: no driver and no rider in two active rides. */
    @Override
    public String id() {
        return "I1";
    }

    @Override
    public List<String> violations(String cityId) {
        return rides.partiesInTwoActiveRides(cityId);
    }
}
