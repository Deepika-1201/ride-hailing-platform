package com.ridehailing.ride.app;

import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.DomainEvent;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.Timers;
import com.ridehailing.platform.Transactions;
import com.ridehailing.pricing.PricingApi;
import com.ridehailing.pricing.PricingApi.ConsumedQuote;
import com.ridehailing.ride.RideDispatchParticipant;
import com.ridehailing.ride.RideDispatchParticipant.SearchStarted;
import com.ridehailing.ride.RideView;
import com.ridehailing.ride.app.RideTransitions.Command;
import com.ridehailing.ride.db.RideRepository;
import com.ridehailing.ride.db.RideRepository.NewRide;
import com.ridehailing.ride.db.RideRepository.RideRow;
import com.ridehailing.ride.events.RideRequested;
import com.ridehailing.rider.RiderApi;
import com.ridehailing.rider.RiderApi.PaymentMethodRef;
import com.ridehailing.shared.Actor;
import com.ridehailing.shared.Ids;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** T1 (LLD §7.2): every check runs inside the transaction, so a rejected booking leaves the quote unused. */
@Service
public class Booking {

    static final int FIRST_SEARCH = 0;

    private final PricingApi pricing;
    private final RiderApi riders;
    private final RideRepository rides;
    private final RideLog log;
    private final RideViews views;
    private final Outbox outbox;
    private final Timers timers;
    private final RideDispatchParticipant participant;
    private final Transactions transactions;
    private final RideMetrics metrics;

    Booking(PricingApi pricing, RiderApi riders, RideRepository rides, RideLog log, RideViews views, Outbox outbox,
            Timers timers, RideDispatchParticipant participant, Transactions transactions, RideMetrics metrics) {
        this.pricing = pricing;
        this.riders = riders;
        this.rides = rides;
        this.log = log;
        this.views = views;
        this.outbox = outbox;
        this.timers = timers;
        this.participant = participant;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    /** {@code paymentMethodId} may be null for the rider's default. */
    public RideView book(UUID riderId, UUID quoteId, UUID paymentMethodId) {
        UUID rideId = Ids.newId();
        return transactions.execute(() -> {
            ConsumedQuote quote = pricing.consume(quoteId, riderId, rideId);
            PaymentMethodRef method = riders.paymentMethod(riderId, paymentMethodId)
                    .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PAYMENT_METHOD_INVALID",
                            "This payment method isn't yours or is no longer active."));
            if (rides.hasActiveRide(riderId)) {
                throw activeRideExists();
            }
            RideRow ride;
            try {
                ride = rides.insert(new NewRide(rideId, riderId, quote.cityId(), quote.category(), quoteId,
                        quote.pickup(), quote.dropoff(), quote.pickupZone(), quote.distanceM(), quote.durationS(),
                        quote.fare().amountPaise(), quote.commission().amountPaise(), quote.fare().currency(),
                        quote.feeRuleId(), method.id(), method.type(),
                        views.riderSnapshot(riders.snapshot(riderId).firstName())));
            } catch (DuplicateKeyException e) {
                // A concurrent booking by the same rider committed first.
                throw activeRideExists();
            }
            log.record(null, ride, Command.BOOK, new Actor(Actor.Type.RIDER, riderId.toString()), null);
            outbox.append(DomainEvent.of(RideRequested.TYPE, RideRequested.VERSION, "ride", rideId, ride.version(),
                    new RideRequested(rideId, riderId, ride.cityId(), ride.category(), ride.pickup(), ride.dropoff(),
                            ride.pickupZone(), quote.fare(), method.type(), quoteId, ride.requestedAt())));
            Duration searchTimeout = participant.searchStarted(new SearchStarted(rideId, ride.cityId(),
                    ride.category(), ride.pickup(), FIRST_SEARCH));
            timers.schedule(RideTimers.SEARCH_TIMEOUT, rideId, ride.requestedAt().plus(searchTimeout),
                    Map.of("generation", ride.searchGeneration()));
            metrics.requested(ride.cityId(), ride.category());
            return views.of(ride);
        });
    }

    private static ApiException activeRideExists() {
        return new ApiException(HttpStatus.CONFLICT, "ACTIVE_RIDE_EXISTS", "You already have a ride in progress.");
    }
}
