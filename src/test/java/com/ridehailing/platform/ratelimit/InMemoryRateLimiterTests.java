package com.ridehailing.platform.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.RateLimiter;
import com.ridehailing.platform.ratelimit.RateLimitProperties.Limit;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;

/** LLD §5.8, V1: the contract in this process's memory, which drops refilled buckets once a minute. */
class InMemoryRateLimiterTests extends RateLimiterContract {

    @Override
    protected RateLimiter newLimiter(RateLimitProperties limits, Clock clock) {
        return new InMemoryRateLimiter(limits, clock);
    }

    @Test
    void manyConcurrentCallersNeverGetMoreThanTheCapacity() throws Exception {
        InMemoryRateLimiter busy = new InMemoryRateLimiter(new RateLimitProperties(Map.of(
                "busy", new Limit(100_000, Duration.ofHours(1), false))), clock);
        LongAdder allowed = new LongAdder();
        List<Future<?>> callers = new ArrayList<>();
        // Platform threads, so calls really run in parallel; any lost update would admit more than the capacity.
        try (var executor = Executors.newFixedThreadPool(16)) {
            for (int caller = 0; caller < 16; caller++) {
                callers.add(executor.submit(() -> {
                    for (int call = 0; call < 10_000; call++) {
                        if (busy.tryAcquire("busy", "shared").allowed()) {
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

        assertThat(((InMemoryRateLimiter) limiter).size()).isEqualTo(2);
    }
}
