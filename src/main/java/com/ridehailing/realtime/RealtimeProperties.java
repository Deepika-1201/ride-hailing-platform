package com.ridehailing.realtime;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * {@code ride.realtime} (LLD §14.7, appendix): where tickets send clients, heartbeats, what makes a session too slow to
 * keep, draining, the operations map and tracking.
 */
@ConfigurationProperties("ride.realtime")
public record RealtimeProperties(
        @DefaultValue("ws://localhost:8080/ws") URI url,
        @DefaultValue("5s") Duration heartbeatEvery,
        @DefaultValue("25s") Duration pingEvery,
        @DefaultValue("60s") Duration idleTimeout,
        @DefaultValue("2s") Duration sendTimeLimit,
        @DefaultValue("16KB") DataSize bufferLimit,
        @DefaultValue("25s") Duration drainSpread,
        @DefaultValue("30s") Duration drainTimeout,
        @DefaultValue("2s") Duration opsEvery,
        @DefaultValue("5000") int opsMaxDrivers,
        @DefaultValue("15s") Duration etaEvery,
        @DefaultValue("2m") Duration arrivingWithin) {
}
