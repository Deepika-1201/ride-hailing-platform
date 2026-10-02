package com.ridehailing.platform;

import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Token buckets per configured limit and key, such as limit {@code otp-per-phone} and key {@code +919845012345}
 * (LLD §5.8). Limits are configured as {@code ride.rate-limits.<name>.capacity} per {@code .period}.
 */
public interface RateLimiter {

    /**
     * Takes one token from the key's bucket under the named limit.
     *
     * @throws IllegalStateException if no limit has that name
     */
    RateDecision tryAcquire(String limit, String key);

    /** Like {@link #tryAcquire}, but a rejection becomes {@code 429 RATE_LIMITED} with {@code Retry-After}. */
    default void acquireOrReject(String limit, String key) {
        RateDecision decision = tryAcquire(limit, key);
        if (!decision.allowed()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests; try again later.",
                    decision.retryAfter(), Map.of());
        }
    }

    /** {@code retryAfter} is how long until a token is available; zero when allowed. */
    record RateDecision(boolean allowed, Duration retryAfter) {

        public static final RateDecision ALLOWED = new RateDecision(true, Duration.ZERO);
    }
}
