package com.ridehailing.ride.app;

import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.Timers;
import com.ridehailing.ride.RideDispatchParticipant;
import com.ridehailing.ride.RideDispatchParticipant.DriverRelease;
import com.ridehailing.ride.RideDispatchParticipant.SearchStarted;
import com.ridehailing.ride.app.RideTransitions.Command;
import com.ridehailing.ride.db.RideRepository;
import com.ridehailing.ride.db.RideRepository.RideRow;
import com.ridehailing.ride.events.DriverUnassigned;
import com.ridehailing.shared.Actor;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/** T6 and T7 (LLD §7.4, §7.9): the ride returns to the search with priority, in the caller's transaction. */
@Component
class Unassignments {

    static final int REASSIGNED_PRIORITY = 1;

    private final RideRepository rides;
    private final RideLog log;
    private final Outbox outbox;
    private final Timers timers;
    private final RideDispatchParticipant participant;

    Unassignments(RideRepository rides, RideLog log, Outbox outbox, Timers timers,
            RideDispatchParticipant participant) {
        this.rides = rides;
        this.log = log;
        this.outbox = outbox;
        this.timers = timers;
        this.participant = participant;
    }

    /**
     * Unassigns the ride's driver: a new search generation with its own timeout, the driver released as
     * {@code release} says, and {@code DriverUnassigned} with {@code eventReason}.
     */
    RideRow unassign(RideRow ride, Command command, Actor actor, String reason, DriverRelease release,
            String eventReason) {
        RideRow unassigned = rides.unassign(ride.id(), ride.version()).orElseThrow();
        log.record(ride, unassigned, command, actor, reason);
        participant.driverReleased(ride.id(), ride.driverId(), release);
        Duration searchTimeout = participant.searchStarted(new SearchStarted(ride.id(), ride.cityId(), ride.category(),
                ride.pickup(), REASSIGNED_PRIORITY));
        timers.scheduleAfter(RideTimers.SEARCH_TIMEOUT, ride.id(), searchTimeout,
                Map.of("generation", unassigned.searchGeneration()));
        outbox.append(DomainEvent.of(DriverUnassigned.TYPE, DriverUnassigned.VERSION, "ride", ride.id(),
                unassigned.version(), new DriverUnassigned(ride.id(), ride.riderId(), ride.driverId(), eventReason,
                        unassigned.searchGeneration(), rides.now())));
        return unassigned;
    }
}
