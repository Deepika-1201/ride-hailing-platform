package com.ridehailing.audit.jobs;

import com.ridehailing.audit.app.AuditPartitionMaintenance;
import com.ridehailing.platform.RecurringJob;
import com.ridehailing.platform.Role;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
class AuditPartitionJob implements RecurringJob {

    private final AuditPartitionMaintenance maintenance;

    AuditPartitionJob(AuditPartitionMaintenance maintenance) {
        this.maintenance = maintenance;
    }

    @Override
    public String name() {
        return "audit-partitions";
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
