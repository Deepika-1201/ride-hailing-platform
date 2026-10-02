package com.ridehailing.platform.timers;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The timer poller's interval and threads, and the failures after which a timer is parked (LLD §5.4). */
@ConfigurationProperties(prefix = "ride.timers")
public record TimerProperties(
        @DefaultValue("250ms") Duration pollInterval,
        @DefaultValue("4") int workers,
        @DefaultValue("10") int maxFailures) {
}
