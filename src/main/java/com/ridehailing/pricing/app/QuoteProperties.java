package com.ridehailing.pricing.app;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code ride.quotes}: how long a quote fixes the price (FR-PR1). */
@ConfigurationProperties("ride.quotes")
public record QuoteProperties(@DefaultValue("5m") Duration ttl) {
}
