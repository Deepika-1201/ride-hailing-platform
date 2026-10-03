package com.ridehailing.ride.app;

import com.ridehailing.platform.Transactions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The ride module's metrics (HLD §15, LLD §16.1), counted after commit so a rollback or a retried attempt never counts.
 * Labelled by city (and category), so each series appears with its city's first ride: Prometheus needs every meter of
 * a name to have the same tag keys, which rules out registering them unlabelled at startup.
 */
@Component
class RideMetrics {

    private final MeterRegistry meters;
    private final Transactions transactions;

    RideMetrics(MeterRegistry meters, Transactions transactions) {
        this.meters = meters;
        this.transactions = transactions;
    }

    /** T1 committed. */
    void requested(String cityId, String category) {
        transactions.afterCommit(() -> meters.counter("ride.requests", "city", cityId, "category", category)
                .increment());
    }

    /** T2 committed: {@code assigned_at − requested_at}, both from the database clock. */
    void assigned(String cityId, Duration sinceRequested) {
        transactions.afterCommit(() -> assignment().tag("city", cityId).register(meters).record(sinceRequested));
    }

    /** T3 committed. */
    void notMatched(String cityId) {
        transactions.afterCommit(() -> meters.counter("rides.not.matched", "city", cityId).increment());
    }

    private static Timer.Builder assignment() {
        return Timer.builder("ride.assignment").description("Booking to assignment").publishPercentileHistogram();
    }
}
