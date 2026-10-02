package com.ridehailing.platform.jobs;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** How often the platform's retention job runs, its batch size, and how long each kind of row is kept (LLD §5.7). */
@ConfigurationProperties(prefix = "ride.retention")
public record RetentionProperties(
        @DefaultValue("1h") Duration interval,
        @DefaultValue("10000") int batchSize,
        @DefaultValue("7d") Duration outbox,
        @DefaultValue("14d") Duration inbox,
        @DefaultValue("14d") Duration failedDeliveries) {
}
