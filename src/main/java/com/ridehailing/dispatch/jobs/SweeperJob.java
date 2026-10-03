package com.ridehailing.dispatch.jobs;

import com.ridehailing.dispatch.app.Sweeper;
import com.ridehailing.platform.RecurringJob;
import com.ridehailing.platform.Role;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
class SweeperJob implements RecurringJob {

    private final Sweeper sweeper;

    SweeperJob(Sweeper sweeper) {
        this.sweeper = sweeper;
    }

    @Override
    public String name() {
        return "sweeper";
    }

    @Override
    public Role role() {
        return Role.DISPATCH;
    }

    @Override
    public Duration interval() {
        return Duration.ofSeconds(5);
    }

    @Override
    public void run() {
        sweeper.sweepAll();
    }
}
