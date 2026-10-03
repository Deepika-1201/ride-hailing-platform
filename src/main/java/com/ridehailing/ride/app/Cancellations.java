package com.ridehailing.ride.app;

import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.Timers;
import com.ridehailing.platform.Transactions;
import com.ridehailing.ride.RideDispatchParticipant;
import com.ridehailing.ride.RideDispatchParticipant.SearchStop;
import com.ridehailing.ride.RideStatus;
import com.ridehailing.ride.RideView;
import com.ridehailing.ride.db.RideRepository;
import com.ridehailing.ride.db.RideRepository.RideRow;
import com.ridehailing.ride.events.RideCancelled;
import com.ridehailing.shared.Actor;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * {@code POST /v1/rides/{id}/cancel} (LLD §7.4). Phase 7 builds T4, the rider cancelling while searching; the other
 * cancellations arrive in phase 8 and answer {@code 409 INVALID_TRANSITION} until then.
 */
@Service
public class Cancellations {

    private final RideRepository rides;
    private final RideLog log;
    private final RideViews views;
    private final Outbox outbox;
    private final Timers timers;
    private final RideDispatchParticipant participant;
    private final Transactions transactions;

    Cancellations(RideRepository rides, RideLog log, RideViews views, Outbox outbox, Timers timers,
            RideDispatchParticipant participant, Transactions transactions) {
        this.rides = rides;
        this.log = log;
        this.views = views;
        this.outbox = outbox;
        this.timers = timers;
        this.participant = participant;
        this.transactions = transactions;
    }

    /** The caller as the ride's rider, else as its driver; anyone else gets {@code 404}. */
    public RideView cancel(UUID callerId, UUID rideId, String reason) {
        return transactions.execute(() -> {
            RideRow ride = rides.lock(rideId).orElseThrow(ApiException::notFound);
            if (ride.riderId().equals(callerId)) {
                return cancelByRider(ride, reason).forRider();
            }
            if (callerId.equals(ride.driverId())) {
                throw invalidTransition(ride);
            }
            throw ApiException.notFound();
        });
    }

    private RideView cancelByRider(RideRow ride, String reason) {
        if (ride.status() == RideStatus.CANCELLED_BY_RIDER) {
            return views.of(ride);
        }
        if (ride.status() != RideStatus.SEARCHING) {
            throw invalidTransition(ride);
        }
        RideRow cancelled = rides.endSearch(ride.id(), ride.version(), RideStatus.CANCELLED_BY_RIDER, "RIDER", reason)
                .orElseThrow();
        participant.searchStopped(ride.id(), SearchStop.RIDER_CANCELLED);
        timers.cancel(RideTimers.SEARCH_TIMEOUT, ride.id());
        log.record(ride, cancelled, "CANCEL", new Actor(Actor.Type.RIDER, ride.riderId().toString()), reason);
        outbox.append(DomainEvent.of(RideCancelled.TYPE, RideCancelled.VERSION, "ride", ride.id(), cancelled.version(),
                new RideCancelled(ride.id(), ride.riderId(), null, ride.cityId(), cancelled.status().name(), "RIDER",
                        reason, ride.paymentMethodId(), ride.paymentMethodType(), cancelled.endedAt())));
        return views.of(cancelled);
    }

    static ApiException invalidTransition(RideRow ride) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                "The ride is " + ride.status() + ", which doesn't allow this.", null,
                Map.of("current_status", ride.status().name(), "current_version", ride.version()));
    }
}
