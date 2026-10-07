package com.ridehailing.platform.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.RateLimiter;
import com.ridehailing.platform.RateLimiter.RateDecision;
import com.ridehailing.platform.Valkey;
import com.ridehailing.platform.valkey.LettuceValkey;
import com.ridehailing.support.Valkeys;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** LLD §5.8, V2: the contract in Valkey, which every node shares, and what it does when Valkey doesn't answer. */
class ValkeyRateLimiterTests extends RateLimiterContract {

    private static final Duration SHORT = Duration.ofMillis(200);

    @Override
    protected RateLimiter newLimiter(RateLimitProperties limits, Clock clock) {
        return new ValkeyRateLimiter(limits, Valkeys.standalone(), clock);
    }

    @Test
    void twoNodesShareOneBucket() {
        RateLimiter other = newLimiter(LIMITS, clock);
        drain("five-an-hour", a, 3);

        assertThat(other.tryAcquire("five-an-hour", a).allowed()).isTrue();
        assertThat(other.tryAcquire("five-an-hour", a).allowed()).isTrue();
        assertThat(limiter.tryAcquire("five-an-hour", a).allowed()).isFalse();
    }

    @Test
    void aBucketLeftAlonePastItsPeriodExpires() {
        drain("five-an-hour", a, 1);

        Long ttl = Valkeys.standalone().await("test", Valkeys.TIMEOUTS.other(),
                Valkeys.standalone().commands().pttl("rl:five-an-hour:" + a));

        assertThat(ttl).isBetween(Duration.ofHours(1).minusMinutes(1).toMillis(), Duration.ofHours(1).toMillis());
    }

    @Test
    void withoutValkeyALimitAllowsUnlessItFailsClosed() {
        try (LettuceValkey unreachable = Valkeys.newStandalone(new Valkey.Timeouts(SHORT, SHORT, SHORT, SHORT))) {
            RateLimiter cut = new ValkeyRateLimiter(LIMITS, unreachable, clock);
            double errors = Valkeys.errors("rate_limit");

            Valkeys.whilePaused(() -> {
                long started = System.nanoTime();
                assertThat(cut.tryAcquire("five-an-hour", a)).isEqualTo(RateDecision.ALLOWED);
                assertThat(cut.tryAcquire("sign-in", a))
                        .isEqualTo(new RateDecision(false, ValkeyRateLimiter.CLOSED_RETRY));
                assertThat(Duration.ofNanos(System.nanoTime() - started)).as("each waits its timeout only")
                        .isLessThan(Duration.ofSeconds(2));
            });

            assertThat(Valkeys.errors("rate_limit")).isEqualTo(errors + 2);
        }
    }
}
