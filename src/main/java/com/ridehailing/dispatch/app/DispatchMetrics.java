package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.DispatchApi.OfferStatus;
import com.ridehailing.platform.Transactions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Dispatch's metrics (HLD §15, LLD §16.1), counted after commit so a rollback or a retried attempt never counts. The
 * outcome counters are registered at zero for every outcome, so an alert on {@code increase(…) > 0} sees the first;
 * the first-offer timer is labelled by city, so it appears with the city's first offer.
 */
@Component
class DispatchMetrics {

    static final List<String> ATTEMPT_OUTCOMES = List.of("OFFERED", "NO_CANDIDATES", "ALL_RESERVATIONS_LOST",
            "INDEX_UNAVAILABLE");

    private final MeterRegistry meters;
    private final Transactions transactions;

    DispatchMetrics(MeterRegistry meters, Transactions transactions) {
        this.meters = meters;
        this.transactions = transactions;
        for (OfferStatus status : OfferStatus.values()) {
            if (status != OfferStatus.PENDING) {
                meters.counter("offers", "outcome", status.name());
            }
        }
        ATTEMPT_OUTCOMES.forEach(outcome -> meters.counter("dispatch.search.attempts", "outcome", outcome));
        meters.counter("dispatch.reservation.conflicts");
    }

    /** A search attempt committed with this outcome, after {@code conflicts} reservations lost to other searches. */
    void attempted(String outcome, long conflicts) {
        transactions.afterCommit(() -> {
            meters.counter("dispatch.search.attempts", "outcome", outcome).increment();
            meters.counter("dispatch.reservation.conflicts").increment(conflicts);
        });
    }

    /** The first offer of a ride's first search committed: offer time − {@code requested_at}, both database times. */
    void firstOffer(String cityId, Duration sinceRequested) {
        transactions.afterCommit(() -> firstOffer().tag("city", cityId).register(meters).record(sinceRequested));
    }

    /** An offer ended with this status. */
    void ended(OfferStatus outcome) {
        transactions.afterCommit(() -> meters.counter("offers", "outcome", outcome.name()).increment());
    }

    private static Timer.Builder firstOffer() {
        return Timer.builder("dispatch.first.offer").description("Booking to first offer").publishPercentileHistogram();
    }
}
