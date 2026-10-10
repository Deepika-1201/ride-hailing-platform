package com.ridehailing.location;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code ride.location} (LLD appendix): how fresh a candidate must be, when silence makes a driver unreachable or
 * idle, how long offline tombstones last, the quality rules (§9.5), and where the live index lives: {@code memory} or
 * {@code valkey} (§1.3).
 */
@ConfigurationProperties("ride.location")
public record LocationProperties(
        @DefaultValue("memory") String store,
        @DefaultValue("30s") Duration freshness,
        @DefaultValue("2m") Duration unreachableAfter,
        @DefaultValue("10m") Duration offlineAfter,
        @DefaultValue("10m") Duration tombstoneTtl,
        @DefaultValue("true") boolean singleProcessCheck,
        @DefaultValue("100") double maxAccuracyM,
        @DefaultValue("150") double maxSpeedKmh,
        @DefaultValue("3") int implausibleReanchor) {

    public LocationProperties {
        if (!Set.of("memory", "valkey").contains(store)) {
            throw new IllegalArgumentException("ride.location.store must be memory or valkey, not " + store);
        }
    }

    public LiveIndex.Quality quality() {
        return new LiveIndex.Quality(maxAccuracyM, maxSpeedKmh / 3.6, implausibleReanchor);
    }
}
