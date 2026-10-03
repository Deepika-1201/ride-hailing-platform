package com.ridehailing.dispatch.jobs;

import com.ridehailing.dispatch.app.DispatchRetention;
import com.ridehailing.platform.RecurringJob;
import com.ridehailing.platform.Role;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
class DispatchRetentionJob implements RecurringJob {

    private final DispatchRetention retention;

    DispatchRetentionJob(DispatchRetention retention) {
        this.retention = retention;
    }

    @Override
    public String name() {
        return "dispatch-retention";
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
