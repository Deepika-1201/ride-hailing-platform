package com.ridehailing.payment.app;

import com.ridehailing.platform.Poller;
import com.ridehailing.platform.Role;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Sends pending attempts and refunds on every worker node (LLD §11.10). */
@Component
class PaymentSender implements Poller {

    static final String NAME = "payment-sender";

    private final PaymentExecutor executor;
    private final PaymentProperties properties;

    PaymentSender(PaymentExecutor executor, PaymentProperties properties) {
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
        return executor.sendNext();
    }
}
