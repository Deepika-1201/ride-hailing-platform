package com.ridehailing.platform.leases;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Leases held continuously, such as the relay's: their TTL and how often the holder renews (LLD §5.5). */
@ConfigurationProperties(prefix = "ride.leases")
public record LeaseProperties(@DefaultValue("10s") Duration ttl, @DefaultValue("3s") Duration renewEvery) {
}
