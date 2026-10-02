package com.ridehailing.platform.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.platform.RateLimiter.RateDecision;
import com.ridehailing.platform.ratelimit.RateLimitProperties.Limit;
import com.ridehailing.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;

/** LLD §5.8: token buckets that allow their capacity, refill continuously, and say when to retry. */
class InMemoryRateLimiterTests {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-02T08:00:00Z"));
    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(new RateLimitProperties(Map.of(
            "five-an-hour", new Limit(5, Duration.ofHours(1)),
            "fifty-an-hour", new Limit(50, Duration.ofHours(1)),
            "busy", new Limit(100_000, Duration.ofHours(1)))), clock);

    @Test
    void allowsTheCapacityThenSaysWhenTheNextTokenArrives() {
        for (int request = 0; request < 5; request++) {
            assertThat(limiter.tryAcquire("five-an-hour", "a").allowed()).isTrue();
        }

        RateDecision rejected = limiter.tryAcquire("five-an-hour", "a");

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter().toMillis()).isEqualTo(Duration.ofMinutes(12).toMillis());
    }

    @Test
    void refillsContinuously() {
        drain("five-an-hour", "a", 5);

        clock.advance(Duration.ofMinutes(12));

        assertThat(limiter.tryAcquire("five-an-hour", "a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("five-an-hour", "a").allowed()).isFalse();
    }

    @Test
    void bucketsArePerLimitAndKey() {
        drain("five-an-hour", "a", 5);

        assertThat(limiter.tryAcquire("five-an-hour", "b").allowed()).isTrue();
        assertThat(limiter.tryAcquire("fifty-an-hour", "a").allowed()).isTrue();
    }

    @Test
    void anUnknownLimitIsAProgrammingError() {
        assertThatThrownBy(() -> limiter.tryAcquire("no-such-limit", "a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ride.rate-limits.no-such-limit");
    }

    @Test
    void concurrentCallersNeverGetMoreThanTheCapacity() throws Exception {
        LongAdder allowed = new LongAdder();
        List<Future<?>> callers = new ArrayList<>();
        // Platform threads, so calls really run in parallel; any lost update would admit more than the capacity.
        try (var executor = Executors.newFixedThreadPool(16)) {
            for (int caller = 0; caller < 16; caller++) {
                callers.add(executor.submit(() -> {
                    for (int call = 0; call < 10_000; call++) {
                        if (limiter.tryAcquire("busy", "shared").allowed()) {
                            allowed.increment();
                        }
                    }
                }));
            }
            for (Future<?> caller : callers) {
                caller.get();
            }
        }

        assertThat(allowed.sum()).isEqualTo(100_000);
    }

    @Test
    void refilledBucketsAreDroppedOnceAMinuteAndPartlyUsedOnesKept() {
        drain("five-an-hour", "idle", 1);
        drain("five-an-hour", "busy", 5);
        clock.advance(Duration.ofMinutes(13));

        limiter.tryAcquire("five-an-hour", "newcomer");

        assertThat(limiter.size()).isEqualTo(2);
    }

    private void drain(String limit, String key, int times) {
        for (int request = 0; request < times; request++) {
            limiter.tryAcquire(limit, key);
        }
    }
}
