package com.ridehailing.ride.app;

import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.Timers;
import com.ridehailing.ride.RideAssignment;
import com.ridehailing.ride.RideStatus;
import com.ridehailing.ride.db.RideRepository;
import com.ridehailing.ride.db.RideRepository.RideRow;
import com.ridehailing.ride.events.DriverAssigned;
import com.ridehailing.shared.Actor;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class Assignments implements RideAssignment {

    private static final SecureRandom PINS = new SecureRandom();

    private final RideRepository rides;
    private final RideLog log;
    private final RideViews views;
    private final Outbox outbox;
    private final Timers timers;
    private final RideMetrics metrics;

    Assignments(RideRepository rides, RideLog log, RideViews views, Outbox outbox, Timers timers, RideMetrics metrics) {
        this.rides = rides;
        this.log = log;
        this.views = views;
        this.outbox = outbox;
        this.timers = timers;
        this.metrics = metrics;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<SearchingRide> lockIfSearching(UUID rideId) {
        return rides.lockForShare(rideId)
                .filter(ride -> ride.status() == RideStatus.SEARCHING)
                .map(ride -> new SearchingRide(ride.id(), ride.cityId(), ride.category(), ride.pickup(),
                        ride.requestedAt()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Assignment assign(AssignDriver command) {
        RideRow ride = rides.lock(command.rideId()).orElseThrow(ApiException::notFound);
        if (command.offerId().equals(ride.offerId()) && command.driverId().equals(ride.driverId())) {
            return new Assignment(views.of(ride), true);
        }
        if (ride.status() != RideStatus.SEARCHING) {
            throw new ApiException(HttpStatus.CONFLICT, "OFFER_NO_LONGER_AVAILABLE", "The ride no longer needs a driver.");
        }
        RideRow assigned;
        try {
            assigned = rides.assign(ride.id(), ride.version(), command.driverId(), command.driver().vehicle().id(),
                    command.offerId(), "%04d".formatted(PINS.nextInt(10_000)), command.promisedPickupEtaS(),
                    views.driverSnapshot(command.driver().firstName(), command.driver().vehicle())).orElseThrow();
        } catch (DuplicateKeyException e) {
            // The driver holds another active ride, which only a newer offer can have given them.
            throw new ApiException(HttpStatus.CONFLICT, "OFFER_NO_LONGER_AVAILABLE",
                    "You already have an active ride; this offer has ended.");
        }
        log.record(ride, assigned, "ACCEPT", new Actor(Actor.Type.DRIVER, command.driverId().toString()), null);
        outbox.append(DomainEvent.of(DriverAssigned.TYPE, DriverAssigned.VERSION, "ride", ride.id(), assigned.version(),
                new DriverAssigned(ride.id(), ride.riderId(), command.driverId(), assigned.vehicleId(),
                        command.offerId(), command.promisedPickupEtaS(), assigned.reassignCount(),
                        assigned.assignedAt())));
        timers.cancel(RideTimers.SEARCH_TIMEOUT, ride.id());
        metrics.assigned(ride.cityId(), Duration.between(ride.requestedAt(), assigned.assignedAt()));
        return new Assignment(views.of(assigned), false);
    }
}
