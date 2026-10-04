package com.ridehailing.ride.app;

import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.DueTimer;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.TimerHandler;
import com.ridehailing.platform.TimerKind;
import com.ridehailing.ride.RideDispatchParticipant;
import com.ridehailing.ride.RideDispatchParticipant.SearchStop;
import com.ridehailing.ride.RideStatus;
import com.ridehailing.ride.app.RideTransitions.Command;
import com.ridehailing.ride.db.RideRepository;
import com.ridehailing.ride.db.RideRepository.RideRow;
import com.ridehailing.ride.events.RideNotMatched;
import com.ridehailing.shared.Actor;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** T3 (LLD §7.3), in the timer's transaction; a timer of an earlier search, or of a ride that moved on, does nothing. */
@Component
class SearchTimeoutHandler implements TimerHandler {

    private final RideRepository rides;
    private final RideLog log;
    private final Outbox outbox;
    private final RideDispatchParticipant participant;
    private final RideMetrics metrics;

    SearchTimeoutHandler(RideRepository rides, RideLog log, Outbox outbox, RideDispatchParticipant participant,
            RideMetrics metrics) {
        this.rides = rides;
        this.log = log;
        this.outbox = outbox;
        this.participant = participant;
        this.metrics = metrics;
    }

    @Override
    public TimerKind kind() {
        return RideTimers.SEARCH_TIMEOUT;
    }

    @Override
    public void fire(DueTimer timer) {
        RideRow ride = rides.lock(timer.aggregateId()).orElse(null);
        if (ride == null || ride.status() != RideStatus.SEARCHING
                || ride.searchGeneration() != timer.payload().path("generation").asInt()) {
            return;
        }
        RideRow ended = rides.endSearch(ride.id(), ride.version(), RideStatus.DRIVER_NOT_FOUND, null, null)
                .orElseThrow();
        participant.searchStopped(ride.id(), SearchStop.SEARCH_TIMEOUT);
        log.record(ride, ended, Command.SEARCH_TIMEOUT, Actor.system("search-timeout"), null);
        outbox.append(DomainEvent.of(RideNotMatched.TYPE, RideNotMatched.VERSION, "ride", ride.id(), ended.version(),
                new RideNotMatched(ride.id(), ride.riderId(), ride.cityId(), ride.category(),
                        Duration.between(ride.requestedAt(), ended.endedAt()).toSeconds(), ended.endedAt())));
        metrics.notMatched(ride.cityId());
    }
}
