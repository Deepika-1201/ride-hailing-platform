package com.ridehailing.pricing.jobs;

import com.ridehailing.platform.RecurringJob;
import com.ridehailing.platform.Role;
import com.ridehailing.pricing.app.PricingRetention;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
class PricingRetentionJob implements RecurringJob {

    private final PricingRetention retention;

    PricingRetentionJob(PricingRetention retention) {
        this.retention = retention;
    }

    @Override
    public String name() {
        return "pricing-retention";
    }

    @Override
    public Role role() {
        return Role.WORKER;
    }

    @Override
    public Duration interval() {
        return Duration.ofHours(1);
    }

    @Override
    public void run() {
        retention.purge();
    }
}
