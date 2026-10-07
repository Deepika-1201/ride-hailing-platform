package com.ridehailing.platform.ratelimit;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code ride.rate-limits.<name>.capacity} requests per {@code .period}, refilled continuously; {@code fail-closed}
 * rejects rather than allows when the limiter's store is unreachable (LLD §5.8, §20).
 */
@ConfigurationProperties(prefix = "ride")
public record RateLimitProperties(Map<String, Limit> rateLimits) {

    public RateLimitProperties {
        rateLimits = rateLimits == null ? Map.of() : Map.copyOf(rateLimits);
    }

    Limit named(String name) {
        Limit limit = rateLimits.get(name);
        if (limit == null) {
            throw new IllegalStateException("ride.rate-limits." + name + " is not configured");
        }
        return limit;
    }

    public record Limit(int capacity, Duration period, @DefaultValue("false") boolean failClosed) {

        public Limit {
            if (capacity < 1 || period == null || period.isNegative() || period.isZero()) {
                throw new IllegalArgumentException("A rate limit needs a positive capacity and period");
            }
        }
    }
}
