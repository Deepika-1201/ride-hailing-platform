package com.ridehailing.dispatch.app;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code ride.dispatch} (LLD §20): the search-task poller, search attempts and unresponsive drivers. */
@ConfigurationProperties("ride.dispatch")
public record DispatchProperties(
        @DefaultValue("250ms") Duration pollInterval,
        @DefaultValue("4") int workers,
        @DefaultValue("20") int candidates,
        @DefaultValue("5") int maxReservationTries,
        @DefaultValue("5s") Duration retryAfter,
        @DefaultValue("1s") Duration contentionRetryAfter,
        @DefaultValue("3") int maxConsecutiveExpired) {
}
