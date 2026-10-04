package com.ridehailing.ride.app;

import com.ridehailing.platform.ApiException;
import com.ridehailing.ride.RideStatus;
import com.ridehailing.ride.app.RideTransitions.Command;
import com.ridehailing.ride.db.RideRepository;
import com.ridehailing.ride.db.RideRepository.RideRow;
import com.ridehailing.ride.db.TransitionRepository;
import com.ridehailing.shared.Actor;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Steps 2–5 of a ride command (LLD §7.1) for a caller who is a party to the ride: lock, authorize, recognize a repeat,
 * look up the transition table. In the caller's transaction.
 */
@Component
class RideCommands {

    private final RideRepository rides;
    private final TransitionRepository transitions;

    RideCommands(RideRepository rides, TransitionRepository transitions) {
        this.rides = rides;
        this.transitions = transitions;
    }

    /** The ride locked for its current driver: {@code 409 RIDE_REASSIGNED} to a former one, else {@code 404}. */
    RideRow lockForDriver(UUID rideId, UUID driverId) {
        RideRow ride = rides.lock(rideId).orElseThrow(ApiException::notFound);
        if (driverId.equals(ride.driverId())) {
            return ride;
        }
        if (formerDriver(ride, driverId)) {
            throw RideErrors.reassigned();
        }
        throw ApiException.notFound();
    }

    /** For a caller who doesn't drive the ride now: an {@code ACCEPT} in its log makes them a former driver (§7.1). */
    boolean formerDriver(RideRow ride, UUID driverId) {
        return commandsBy(ride, driverId).contains(Command.ACCEPT.name());
    }

    /** The driver's own latest entry in the ride's log, which has their {@code ACCEPT} at least, is a cancellation. */
    boolean cancelledBy(RideRow ride, UUID driverId) {
        return commandsBy(ride, driverId).getLast().equals(Command.CANCEL.name());
    }

    /** The table's target for the command, or {@code 409 INVALID_TRANSITION}. */
    static RideStatus target(RideRow ride, Command command, Actor.Type actor) {
        return RideTransitions.target(ride.status(), command, actor).orElseThrow(() -> RideErrors.invalidTransition(ride));
    }

    private List<String> commandsBy(RideRow ride, UUID driverId) {
        return transitions.commandsBy(ride.id(), driverId.toString());
    }
}
