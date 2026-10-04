package com.ridehailing.ride.jobs;

import com.ridehailing.platform.RecurringJob;
import com.ridehailing.platform.Role;
import com.ridehailing.ride.app.StuckRides;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
class StuckRidesJob implements RecurringJob {

    private final StuckRides stuckRides;

    StuckRidesJob(StuckRides stuckRides) {
        this.stuckRides = stuckRides;
    }

    @Override
    public String name() {
        return "stuck-rides";
    }

    @Override
    public Role role() {
        return Role.WORKER;
    }

    @Override
    public Duration interval() {
        return Duration.ofMinutes(1);
    }

    @Override
    public void run() {
        stuckRides.report();
    }
}
