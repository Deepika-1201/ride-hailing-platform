package com.ridehailing.pricing.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.pricing.db.SurgeRuleRepository.SurgeRule;
import com.ridehailing.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** HLD §5.4, LLD §10.3: entries live 60 s, and a change in this process clears its city's entry at once. */
class SurgeRuleCacheTests {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-02T10:00:00Z"));
    private final AtomicInteger loads = new AtomicInteger();

    @Test
    void anEntryIsReusedForSixtySeconds() {
        SurgeRuleCache cache = new SurgeRuleCache(city -> load(), clock);

        cache.of("blr");
        clock.advance(Duration.ofSeconds(59));
        cache.of("blr");
        assertThat(loads).hasValue(1);

        clock.advance(Duration.ofSeconds(1));
        cache.of("blr");
        assertThat(loads).hasValue(2);
    }

    @Test
    void anInvalidatedCityIsReloadedAtOnce() {
        SurgeRuleCache cache = new SurgeRuleCache(city -> load(), clock);
        cache.of("blr");
        cache.of("hyd");

        cache.invalidate("blr");
        cache.of("blr");
        cache.of("hyd");

        assertThat(loads).hasValue(3);
    }

    @Test
    void aLoadOverlappingAnInvalidationIsNotKept() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SurgeRuleCache cache = new SurgeRuleCache(city -> {
            if (loads.get() == 0) {
                loading.countDown();
                await(release);
            }
            return load();
        }, clock);

        CompletableFuture<List<SurgeRule>> early = CompletableFuture.supplyAsync(() -> cache.of("blr"));
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
        cache.invalidate("blr");
        release.countDown();
        early.get(5, TimeUnit.SECONDS);

        cache.of("blr");
        assertThat(loads).as("the overlapping load was used once, then reloaded").hasValue(2);
    }

    private List<SurgeRule> load() {
        loads.incrementAndGet();
        return List.of();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
