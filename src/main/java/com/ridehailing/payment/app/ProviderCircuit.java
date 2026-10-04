package com.ridehailing.payment.app;

import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The circuit breaker in front of the provider, one per process (LLD §11.10). Consecutive calls without an answer
 * open it; while it is open the executor claims nothing, so attempts wait as {@code PENDING} instead of piling up as
 * {@code UNKNOWN}. Once it closes again, one more failure reopens it at once; one answer resets the count.
 */
@Component
class ProviderCircuit {

    private static final Logger log = LoggerFactory.getLogger(ProviderCircuit.class);

    private final int threshold;
    private final Duration openFor;
    private int failures;
    private boolean open;
    private long closesAt;

    ProviderCircuit(PaymentProperties properties) {
        this.threshold = properties.breakerFailures();
        this.openFor = properties.breakerOpenFor();
    }

    synchronized boolean allowsCalls() {
        return !open || System.nanoTime() - closesAt >= 0;
    }

    /** The call's answer, or null if it had none: an exception means the outcome is unknown (LLD §11.2). */
    ProviderAnswer call(Supplier<ProviderAnswer> call) {
        ProviderAnswer answer;
        try {
            answer = call.get();
        } catch (RuntimeException e) {
            failed();
            log.warn("A payment provider call had no answer: {}", e.toString());
            return null;
        }
        answered();
        return answer;
    }

    private synchronized void answered() {
        failures = 0;
        open = false;
    }

    private synchronized void failed() {
        failures++;
        if (failures >= threshold) {
            open = true;
            closesAt = System.nanoTime() + openFor.toNanos();
            failures = threshold - 1;
            log.warn("Payment provider circuit open for {} after {} calls without an answer", openFor, threshold);
        }
    }
}
