package com.ridehailing.ride.app;

import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.Timers;
import com.ridehailing.platform.Transactions;
import com.ridehailing.pricing.PricingApi;
import com.ridehailing.ride.RideDispatchParticipant;
import com.ridehailing.ride.RideDispatchParticipant.DriverRelease;
import com.ridehailing.ride.RideDispatchParticipant.SearchStop;
import com.ridehailing.ride.RideOperations;
import com.ridehailing.ride.RideStatus;
import com.ridehailing.ride.RideView;
import com.ridehailing.ride.app.Fees.RideFee;
import com.ridehailing.ride.app.RideTransitions.Command;
import com.ridehailing.ride.db.FlagRepository;
import com.ridehailing.ride.db.FlagRepository.FlagKind;
import com.ridehailing.ride.db.RideRepository;
import com.ridehailing.ride.db.RideRepository.RideRow;
import com.ridehailing.ride.events.RideCancelled;
import com.ridehailing.shared.Actor;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * {@code POST /v1/rides/{id}/cancel} and the operations cancel (LLD §7.4): the outcome depends on the caller and the
 * state. T4 and T8 for the rider, T6 and T11 for the driver, T13 for operations.
 */
@Service
public class Cancellations implements RideOperations {

    private final RideRepository rides;
    private final RideCommands commands;
    private final Unassignments unassignments;
    private final RideLog log;
    private final RideViews views;
    private final FlagRepository flags;
    private final Outbox outbox;
    private final Timers timers;
    private final RideDispatchParticipant participant;
    private final PricingApi pricing;
    private final Transactions transactions;

    Cancellations(RideRepository rides, RideCommands commands, Unassignments unassignments, RideLog log,
            RideViews views, FlagRepository flags, Outbox outbox, Timers timers, RideDispatchParticipant participant,
            PricingApi pricing, Transactions transactions) {
        this.rides = rides;
        this.commands = commands;
        this.unassignments = unassignments;
        this.log = log;
        this.views = views;
        this.flags = flags;
        this.outbox = outbox;
        this.timers = timers;
        this.participant = participant;
        this.pricing = pricing;
        this.transactions = transactions;
    }

    /** The caller as the ride's rider, else as its driver, current or former; anyone else gets {@code 404}. */
    public RideView cancel(UUID callerId, UUID rideId, String reason) {
        return transactions.execute(() -> {
            RideRow ride = rides.lock(rideId).orElseThrow(ApiException::notFound);
            if (ride.riderId().equals(callerId)) {
                return cancelByRider(ride, reason).forRider();
            }
            if (callerId.equals(ride.driverId())) {
                return cancelByDriver(ride, callerId, reason);
            }
            if (commands.formerDriver(ride, callerId)) {
                if (commands.cancelledBy(ride, callerId)) {
                    return views.of(ride).forReleasedDriver();
                }
                throw RideErrors.reassigned();
            }
            throw ApiException.notFound();
        });
    }

    @Override
    public RideView cancelBySystem(UUID rideId, Actor ops, String reason, Optional<FeeRequest> fee) {
        return transactions.execute(() -> {
            RideRow ride = rides.lock(rideId).orElseThrow(ApiException::notFound);
            RideStatus to = RideCommands.target(ride, Command.CANCEL, ops.type());
            Optional<RideFee> chosen = Fees.chosenByOperations(fee, pricing.feeRule(ride.feeRuleId()));
            return end(ride, to, ops, "SYSTEM", reason, chosen, DriverRelease.OPS_CANCELLED, SearchStop.OPS_CANCELLED)
                    .forOperations();
        });
    }

    /** T4 while searching, T8 after assignment with the fee of ride lifecycle §5. */
    private RideView cancelByRider(RideRow ride, String reason) {
        if (ride.status() == RideStatus.CANCELLED_BY_RIDER) {
            return views.of(ride);
        }
        RideStatus to = RideCommands.target(ride, Command.CANCEL, Actor.Type.RIDER);
        Optional<RideFee> fee = Fees.onRiderCancel(ride, pricing.feeRule(ride.feeRuleId()), rides.now());
        return end(ride, to, new Actor(Actor.Type.RIDER, ride.riderId().toString()), "RIDER", reason, fee,
                DriverRelease.RIDER_CANCELLED, SearchStop.RIDER_CANCELLED);
    }

    /** T6 before arrival returns the ride to the search; T11 at the pickup ends it, flagged for review. */
    private RideView cancelByDriver(RideRow ride, UUID driverId, String reason) {
        if (commands.cancelledBy(ride, driverId)) {
            return views.of(ride).forReleasedDriver();
        }
        RideStatus to = RideCommands.target(ride, Command.CANCEL, Actor.Type.DRIVER);
        Actor driver = new Actor(Actor.Type.DRIVER, driverId.toString());
        if (to == RideStatus.SEARCHING) {
            return views.of(unassignments.unassign(ride, Command.CANCEL, driver, reason,
                    DriverRelease.DRIVER_CANCELLED, "DRIVER_CANCELLED")).forReleasedDriver();
        }
        RideView ended = end(ride, to, driver, "DRIVER", reason, Optional.empty(), DriverRelease.DRIVER_CANCELLED,
                null);
        flags.open(ride.id(), FlagKind.DRIVER_CANCELLED_AT_PICKUP, "{}");
        return ended.forReleasedDriver();
    }

    /**
     * Ends the ride with a cancellation: while searching the search stops for {@code stop}; with a driver, the driver
     * is released for {@code release}. The search timer goes in both cases (it is gone already once a driver was
     * assigned).
     */
    private RideView end(RideRow ride, RideStatus to, Actor actor, String cancelledBy, String reason,
            Optional<RideFee> fee, DriverRelease release, SearchStop stop) {
        RideRow ended = rides.end(ride.id(), ride.version(), ride.status(), to, cancelledBy, reason,
                fee.map(RideFee::purpose).orElse(null), fee.map(RideFee::amountPaise).orElse(null)).orElseThrow();
        if (ride.status() == RideStatus.SEARCHING) {
            participant.searchStopped(ride.id(), stop);
            timers.cancel(RideTimers.SEARCH_TIMEOUT, ride.id());
        } else {
            participant.driverReleased(ride.id(), ride.driverId(), release);
        }
        log.record(ride, ended, Command.CANCEL, actor, reason);
        outbox.append(DomainEvent.of(RideCancelled.TYPE, RideCancelled.VERSION, "ride", ride.id(), ended.version(),
                new RideCancelled(ride.id(), ride.riderId(), ride.driverId(), ride.cityId(), ended.status().name(),
                        cancelledBy, reason, fee.map(RideFee::event).orElse(null), ride.paymentMethodId(),
                        ride.paymentMethodType(), ended.endedAt())));
        return views.of(ended);
    }
}
