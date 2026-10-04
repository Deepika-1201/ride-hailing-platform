package com.ridehailing.ride.app;

import static java.nio.charset.StandardCharsets.US_ASCII;

import com.ridehailing.location.LiveIndex;
import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.Transactions;
import com.ridehailing.pricing.PricingApi;
import com.ridehailing.pricing.PricingApi.FeeTerms;
import com.ridehailing.ride.RideDispatchParticipant;
import com.ridehailing.ride.RideDispatchParticipant.DriverRelease;
import com.ridehailing.ride.RideStatus;
import com.ridehailing.ride.RideView;
import com.ridehailing.ride.app.Fees.RideFee;
import com.ridehailing.ride.app.RideTransitions.Command;
import com.ridehailing.ride.db.FlagRepository;
import com.ridehailing.ride.db.FlagRepository.FlagKind;
import com.ridehailing.ride.db.RideRepository;
import com.ridehailing.ride.db.RideRepository.RideRow;
import com.ridehailing.ride.events.DriverArrived;
import com.ridehailing.ride.events.RideCancelled;
import com.ridehailing.ride.events.TripCompleted;
import com.ridehailing.ride.events.TripStarted;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.Money;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** The assigned driver's commands: arrive (T5), start with the PIN (T9), no-show (T10) and complete (T12). */
@Service
public class DriverCommands {

    static final int MAX_PIN_ATTEMPTS = 5;
    static final int ARRIVED_FAR_M = 300;
    static final String NO_SHOW = "NO_SHOW";

    private final RideRepository rides;
    private final RideCommands commands;
    private final RideLog log;
    private final RideViews views;
    private final FlagRepository flags;
    private final Outbox outbox;
    private final RideDispatchParticipant participant;
    private final PricingApi pricing;
    private final LiveIndex index;
    private final Transactions transactions;
    private final JsonMapper json;

    DriverCommands(RideRepository rides, RideCommands commands, RideLog log, RideViews views, FlagRepository flags,
            Outbox outbox, RideDispatchParticipant participant, PricingApi pricing, LiveIndex index,
            Transactions transactions, JsonMapper json) {
        this.rides = rides;
        this.commands = commands;
        this.log = log;
        this.views = views;
        this.flags = flags;
        this.outbox = outbox;
        this.participant = participant;
        this.pricing = pricing;
        this.index = index;
        this.transactions = transactions;
        this.json = json;
    }

    /** T5. The driver's position is read before the transaction, without locks (LLD §7.5). */
    public RideView arrive(UUID driverId, UUID rideId) {
        Integer distanceM = rides.find(rideId)
                .flatMap(ride -> index.position(ride.cityId(), driverId)
                        .map(position -> (int) Math.round(position.position().metresTo(ride.pickup()))))
                .orElse(null);
        return transactions.execute(() -> {
            RideRow ride = commands.lockForDriver(rideId, driverId);
            if (ride.status() == RideStatus.DRIVER_ARRIVED) {
                return views.of(ride).forDriver();
            }
            RideStatus to = RideCommands.target(ride, Command.ARRIVE, Actor.Type.DRIVER);
            RideRow arrived = rides.advance(ride.id(), ride.version(), ride.status(), to).orElseThrow();
            log.record(ride, arrived, Command.ARRIVE, driver(driverId), null);
            if (distanceM == null || distanceM > ARRIVED_FAR_M) {
                flags.open(ride.id(), FlagKind.ARRIVED_FAR, json.writeValueAsString(distanceM == null
                        ? Map.of("position", "unknown") : Map.of("distance_m", distanceM)));
            }
            outbox.append(DomainEvent.of(DriverArrived.TYPE, DriverArrived.VERSION, "ride", ride.id(),
                    arrived.version(), new DriverArrived(ride.id(), ride.riderId(), driverId, distanceM,
                            arrived.arrivedAt())));
            return views.of(arrived).forDriver();
        });
    }

    /** T9. A wrong PIN is answered rather than thrown, so its count commits (LLD §7.6). */
    public StartOutcome start(UUID driverId, UUID rideId, String pin) {
        return transactions.execute(() -> {
            RideRow ride = commands.lockForDriver(rideId, driverId);
            if (ride.status() == RideStatus.IN_TRIP || ride.status() == RideStatus.COMPLETED) {
                return new StartOutcome(views.of(ride).forDriver(), null);
            }
            RideStatus to = RideCommands.target(ride, Command.START, Actor.Type.DRIVER);
            if (ride.pinAttempts() >= MAX_PIN_ATTEMPTS) {
                throw new ApiException(HttpStatus.CONFLICT, "PIN_LOCKED",
                        "Five wrong PINs: the trip can't start; cancel the ride or contact operations.");
            }
            if (!MessageDigest.isEqual(pin.getBytes(US_ASCII), ride.pin().getBytes(US_ASCII))) {
                RideRow counted = rides.countWrongPin(ride.id());
                int attemptsLeft = MAX_PIN_ATTEMPTS - counted.pinAttempts();
                if (attemptsLeft == 0) {
                    flags.open(ride.id(), FlagKind.PIN_LOCKED, "{}");
                }
                return new StartOutcome(null, attemptsLeft);
            }
            RideRow started = rides.advance(ride.id(), ride.version(), ride.status(), to).orElseThrow();
            log.record(ride, started, Command.START, driver(driverId), null);
            participant.tripStarted(ride.id(), driverId);
            outbox.append(DomainEvent.of(TripStarted.TYPE, TripStarted.VERSION, "ride", ride.id(), started.version(),
                    new TripStarted(ride.id(), ride.riderId(), driverId, started.startedAt())));
            return new StartOutcome(views.of(started).forDriver(), null);
        });
    }

    /** T10, once the rider has had the fee rule's waiting time since the driver arrived (LLD §7.7). */
    public RideView noShow(UUID driverId, UUID rideId) {
        return transactions.execute(() -> {
            RideRow ride = commands.lockForDriver(rideId, driverId);
            if (ride.status() == RideStatus.CANCELLED_BY_DRIVER && NO_SHOW.equals(ride.cancelReason())) {
                return views.of(ride).forDriver();
            }
            RideStatus to = RideCommands.target(ride, Command.NO_SHOW, Actor.Type.DRIVER);
            FeeTerms rule = pricing.feeRule(ride.feeRuleId());
            if (!Fees.noShowAllowed(ride, rule, rides.now())) {
                throw new ApiException(HttpStatus.CONFLICT, "NO_SHOW_TOO_EARLY", "The rider still has time to come.",
                        null, Map.of("available_at", Fees.noShowFrom(ride, rule).toString()));
            }
            Optional<RideFee> fee = Fees.onNoShow(rule);
            RideRow ended = rides.end(ride.id(), ride.version(), ride.status(), to, "DRIVER", NO_SHOW,
                    fee.map(RideFee::purpose).orElse(null), fee.map(RideFee::amountPaise).orElse(null)).orElseThrow();
            log.record(ride, ended, Command.NO_SHOW, driver(driverId), NO_SHOW);
            participant.driverReleased(ride.id(), driverId, DriverRelease.NO_SHOW);
            outbox.append(DomainEvent.of(RideCancelled.TYPE, RideCancelled.VERSION, "ride", ride.id(), ended.version(),
                    new RideCancelled(ride.id(), ride.riderId(), driverId, ride.cityId(), ended.status().name(),
                            "DRIVER", NO_SHOW, fee.map(RideFee::event).orElse(null), ride.paymentMethodId(),
                            ride.paymentMethodType(), ended.endedAt())));
            return views.of(ended).forDriver();
        });
    }

    /** T12: the fare is the quoted fare (FR-RD6). */
    public RideView complete(UUID driverId, UUID rideId) {
        return transactions.execute(() -> {
            RideRow ride = commands.lockForDriver(rideId, driverId);
            if (ride.status() == RideStatus.COMPLETED) {
                return views.of(ride).forDriver();
            }
            RideStatus to = RideCommands.target(ride, Command.COMPLETE, Actor.Type.DRIVER);
            RideRow completed = rides.advance(ride.id(), ride.version(), ride.status(), to).orElseThrow();
            log.record(ride, completed, Command.COMPLETE, driver(driverId), null);
            participant.driverReleased(ride.id(), driverId, DriverRelease.COMPLETED);
            outbox.append(DomainEvent.of(TripCompleted.TYPE, TripCompleted.VERSION, "ride", ride.id(),
                    completed.version(), new TripCompleted(ride.id(), ride.riderId(), driverId, ride.cityId(),
                            ride.category(), new Money(ride.farePaise(), ride.currency()),
                            new Money(ride.commissionPaise(), ride.currency()), ride.paymentMethodId(),
                            ride.paymentMethodType(), completed.completedAt())));
            return views.of(completed).forDriver();
        });
    }

    private static Actor driver(UUID driverId) {
        return new Actor(Actor.Type.DRIVER, driverId.toString());
    }

    /** Either the started ride, or the attempts left after a wrong PIN. */
    public record StartOutcome(RideView ride, Integer attemptsLeft) {
    }
}
