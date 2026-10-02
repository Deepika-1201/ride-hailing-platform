package com.ridehailing.platform.ratelimit;

import com.ridehailing.platform.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * V1's limiter: token buckets in this process's memory (LLD §5.8), so limits hold per node. Full buckets are dropped
 * at most once a minute, so memory follows the keys in active use.
 */
@Component
class InMemoryRateLimiter implements RateLimiter {

    private static final long SWEEP_EVERY = TimeUnit.MINUTES.toNanos(1);

    private final RateLimitProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong lastSweep;

    InMemoryRateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.lastSweep = new AtomicLong(now());
    }

    @Override
    public RateDecision tryAcquire(String limit, String key) {
        RateLimitProperties.Limit configured = properties.named(limit);
        long now = now();
        sweepIfDue(now);
        RateDecision[] decision = new RateDecision[1];
        // compute() runs under the key's lock, so a take never races the sweep's removal of the same bucket.
        buckets.compute(limit + ":" + key, (_, bucket) -> {
            Bucket current = bucket != null ? bucket : new Bucket(configured, now);
            decision[0] = current.take(now);
            return current;
        });
        return decision[0];
    }

    int size() {
        return buckets.size();
    }

    private void sweepIfDue(long now) {
        long last = lastSweep.get();
        if (now - last >= SWEEP_EVERY && lastSweep.compareAndSet(last, now)) {
            buckets.keySet().forEach(key -> buckets.computeIfPresent(key, (_, bucket) -> bucket.isFull(now) ? null : bucket));
        }
    }

    private long now() {
        Instant instant = clock.instant();
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    /** Not thread-safe on its own: only touched inside the map's per-key compute. */
    private static final class Bucket {

        private final double capacity;
        private final double tokensPerNano;
        private double tokens;
        private long refilledAt;

        Bucket(RateLimitProperties.Limit limit, long now) {
            this.capacity = limit.capacity();
            this.tokensPerNano = limit.capacity() / (double) limit.period().toNanos();
            this.tokens = capacity;
            this.refilledAt = now;
        }

        RateDecision take(long now) {
            refill(now);
            if (tokens >= 1) {
                tokens -= 1;
                return RateDecision.ALLOWED;
            }
            return new RateDecision(false, Duration.ofNanos((long) Math.ceil((1 - tokens) / tokensPerNano)));
        }

        boolean isFull(long now) {
            refill(now);
            return tokens >= capacity;
        }

        private void refill(long now) {
            if (now > refilledAt) {
                tokens = Math.min(capacity, tokens + (now - refilledAt) * tokensPerNano);
                refilledAt = now;
            }
        }
    }
}
