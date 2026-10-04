package com.ridehailing.payment.app;

import com.ridehailing.platform.Poller;
import com.ridehailing.platform.Role;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Checks unknown attempts and refunds, and those whose sender died, on every worker node (LLD §11.10). */
@Component
class PaymentChecker implements Poller {

    static final String NAME = "payment-checker";

    private final PaymentExecutor executor;
    private final PaymentProperties properties;

    PaymentChecker(PaymentExecutor executor, PaymentProperties properties) {
        this.executor = executor;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Role role() {
        return Role.WORKER;
    }

    @Override
    public int threads() {
        return properties.workers();
    }

    @Override
    public Duration interval() {
        return properties.pollInterval();
    }

    @Override
    public boolean poll() {
        return executor.checkNext();
    }
}
