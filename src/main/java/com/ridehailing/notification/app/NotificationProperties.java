package com.ridehailing.notification.app;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code ride.notifications} (LLD §20, §15.4). {@code backoff[n]} is the wait after failed attempt {@code n + 1}; the
 * failure after the last wait makes the delivery {@code DEAD}.
 */
@ConfigurationProperties("ride.notifications")
public record NotificationProperties(
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("1") int workers,
        @DefaultValue("30s") Duration lease,
        @DefaultValue({"1s", "5s", "30s", "2m", "5m"}) List<Duration> backoff) {

    public NotificationProperties {
        backoff = List.copyOf(backoff);
    }
}
