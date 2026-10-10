package com.ridehailing.location.jobs;

import com.ridehailing.location.trips.TripPointPartitionMaintenance;
import com.ridehailing.platform.RecurringJob;
import com.ridehailing.platform.Role;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
class TripPointPartitionJob implements RecurringJob {

    private final TripPointPartitionMaintenance maintenance;

    TripPointPartitionJob(TripPointPartitionMaintenance maintenance) {
        this.maintenance = maintenance;
    }

    @Override
    public String name() {
        return "trip-point-partitions";
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
        maintenance.maintain();
    }
}
