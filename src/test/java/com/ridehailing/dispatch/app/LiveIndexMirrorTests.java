package com.ridehailing.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.ridehailing.dispatch.AvailabilityStatus;
import com.ridehailing.dispatch.db.AvailabilityRepository.AvailabilityRow;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.index.InMemoryLiveIndex;
import com.ridehailing.shared.Ids;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** LLD §8.10: a failed mirror write is counted and logged, never thrown at the change that committed. */
class LiveIndexMirrorTests {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AvailabilityRow row = new AvailabilityRow(Ids.newId(), "blr", AvailabilityStatus.AVAILABLE, "MINI",
            Ids.newId(), null, null, 0, Instant.now(), Instant.now(), 1);

    @Test
    void aWriteTheIndexTakesIsReported() {
        LiveIndex index = new InMemoryLiveIndex(Clock.systemUTC(), Duration.ofSeconds(30), Duration.ofMinutes(10));
        LiveIndexMirror mirror = new LiveIndexMirror(index, meters);

        assertThat(mirror.mirror(row)).isTrue();
        assertThat(mirror.mirror(row)).as("the same version again").isFalse();
        assertThat(meters.counter("live.index.mirror.failures").count()).isZero();
    }

    @Test
    void aFailedWriteIsCountedNotThrown() {
        LiveIndex broken = (LiveIndex) Proxy.newProxyInstance(LiveIndex.class.getClassLoader(),
                new Class<?>[] {LiveIndex.class}, (proxy, method, args) -> {
                    throw new IllegalStateException("index unavailable");
                });
        LiveIndexMirror mirror = new LiveIndexMirror(broken, meters);

        assertThat(mirror.mirror(row)).isFalse();
        assertThatCode(() -> mirror.afterCommit(row)).as("outside a transaction it applies at once")
                .doesNotThrowAnyException();
        assertThat(meters.counter("live.index.mirror.failures").count()).isEqualTo(2);
    }
}
