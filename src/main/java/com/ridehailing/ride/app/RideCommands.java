package com.ridehailing.ride.app;

import com.ridehailing.platform.ApiException;
import com.ridehailing.ride.RideStatus;
import com.ridehailing.ride.app.RideTransitions.Command;
import com.ridehailing.ride.db.FlagRepository;
import com.ridehailing.ride.db.FlagRepository.FlagKind;
import com.ridehailing.ride.db.RideRepository;
import com.ridehailing.ride.db.RideRepository.RideRow;
import com.ridehailing.ride.db.TransitionRepository;
import com.ridehailing.shared.Actor;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Steps 2–5 of a ride command (LLD §7.1) for a caller who is a party to the ride: lock, authorize, recognize a repeat,
 * look up the transition table. In the caller's transaction.
 */
@Component
class RideCommands {

    private final RideRepository rides;
    private final TransitionRepository transitions;
    private final FlagRepository flags;
    private final JsonMapper json;

    RideCommands(RideRepository rides, TransitionRepository transitions, FlagRepository flags, JsonMapper json) {
        this.rides = rides;
        this.transitions = transitions;
        this.flags = flags;
        this.json = json;
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

    /**
     * Like {@link #lockForDriver}, for a command an offline app may send late (LLD §7.10): from a former driver with a
     * device time it flags {@code OFFLINE_CONFLICT}, since a trip may be under way, and answers empty so the caller
     * commits the flag before answering {@code 409 RIDE_REASSIGNED}.
     */
    Optional<RideRow> lockForDriver(UUID rideId, UUID driverId, Command command, Instant deviceTime) {
        RideRow ride = rides.lock(rideId).orElseThrow(ApiException::notFound);
        if (driverId.equals(ride.driverId())) {
            return Optional.of(ride);
        }
        if (!formerDriver(ride, driverId)) {
            throw ApiException.notFound();
        }
        if (deviceTime == null) {
            throw RideErrors.reassigned();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("command", command.name());
        details.put("device_time", deviceTime.toString());
        details.put("late_driver_id", driverId.toString());
        details.put("driver_id", ride.driverId() == null ? null : ride.driverId().toString());
        flags.open(ride.id(), FlagKind.OFFLINE_CONFLICT, json.writeValueAsString(details));
        return Optional.empty();
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
