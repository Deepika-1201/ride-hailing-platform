package com.ridehailing.payment.app;

import com.ridehailing.platform.Transactions;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code payment_charges_total{outcome}} (HLD §15, LLD §16.1, §11.10): attempt outcomes as they are recorded, counted
 * after commit. Every outcome is registered at zero, so an alert on {@code increase(…) > 0} sees the first.
 */
@Component
class PaymentMetrics {

    static final String SUCCEEDED = "SUCCEEDED";
    static final String FAILED = "FAILED";
    static final String UNKNOWN = "UNKNOWN";
    static final String UNRESOLVED = "UNRESOLVED";

    private static final String CHARGES = "payment.charges";

    private final MeterRegistry meters;
    private final Transactions transactions;

    PaymentMetrics(MeterRegistry meters, Transactions transactions) {
        this.meters = meters;
        this.transactions = transactions;
        List.of(SUCCEEDED, FAILED, UNKNOWN, UNRESOLVED).forEach(outcome -> meters.counter(CHARGES, "outcome", outcome));
    }

    void recorded(String outcome) {
        transactions.afterCommit(() -> meters.counter(CHARGES, "outcome", outcome).increment());
    }
}
