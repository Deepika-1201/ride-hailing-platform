package com.ridehailing.location;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code ride.location} (LLD appendix): how fresh a candidate must be, when silence makes a driver unreachable or
 * idle, how long offline tombstones last, and the live index's store.
 */
@ConfigurationProperties("ride.location")
public record LocationProperties(
        @DefaultValue("memory") String store,
        @DefaultValue("30s") Duration freshness,
        @DefaultValue("2m") Duration unreachableAfter,
        @DefaultValue("10m") Duration offlineAfter,
        @DefaultValue("10m") Duration tombstoneTtl,
        @DefaultValue("true") boolean singleProcessCheck) {
}
