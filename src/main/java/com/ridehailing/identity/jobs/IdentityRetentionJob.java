package com.ridehailing.identity.jobs;

import com.ridehailing.identity.app.IdentityRetention;
import com.ridehailing.platform.RecurringJob;
import com.ridehailing.platform.Role;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
class IdentityRetentionJob implements RecurringJob {

    private final IdentityRetention retention;

    IdentityRetentionJob(IdentityRetention retention) {
        this.retention = retention;
    }

    @Override
    public String name() {
        return "identity-retention";
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
