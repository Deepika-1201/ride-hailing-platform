package com.ridehailing.location.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.MirrorState;
import com.ridehailing.location.LiveIndex.Status;
import com.ridehailing.location.LiveIndexContract;
import com.ridehailing.platform.Valkey;
import com.ridehailing.platform.Valkey.Script;
import com.ridehailing.shared.Ids;
import com.ridehailing.support.Valkeys;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The contract on one Valkey node (LLD §17.1), and what only the Valkey index has to do. */
class ValkeyLiveIndexTests extends LiveIndexContract {

    private final Valkey valkey = Valkeys.standalone();

    @Override
    protected LiveIndex newIndex(Clock clock, Duration freshness, Duration tombstoneTtl) {
        return new ValkeyLiveIndex(valkey, clock, freshness, tombstoneTtl);
    }

    @Test
    void untilItBeginsTheEpochIsNow() {
        assertThat(index.epoch(city)).isEqualTo(clock.instant());
        clock.advance(Duration.ofSeconds(5));
        assertThat(index.epoch(city)).isEqualTo(clock.instant());

        index.beginEpoch(city);
        Instant begun = clock.instant();
        clock.advance(Duration.ofSeconds(5));

        assertThat(index.epoch(city)).isEqualTo(begun);
    }

    @Test
    void aCityThatLostItsDataBeginsAgainAndHoldsNothing() {
        index.beginEpoch(city);
        at(north(100));

        List<String> keys = valkey.await("test", Valkeys.TIMEOUTS.other(), valkey.commands().keys("{" + city + "}:*"));
        valkey.await("test", Valkeys.TIMEOUTS.other(), valkey.commands().del(keys.toArray(String[]::new)));

        assertThat(index.beginEpoch(city)).isTrue();
        assertThat(index.mirrored(city)).isEmpty();
        assertThat(index.nearby(city, MINI, HERE, 1_000, 5)).isEmpty();
    }

    @Test
    void aTombstoneWhoseKeyExpiredLeavesTheDriverSetWhenNextRead() throws InterruptedException {
        LiveIndex quick = new ValkeyLiveIndex(valkey, clock, FRESHNESS, Duration.ofMillis(50));
        UUID driver = Ids.newId();
        quick.mirror(city, driver, new MirrorState(Status.OFFLINE, 2, null, null));
        assertThat(isMember(driver)).isTrue();

        clock.advance(Duration.ofMillis(50));
        assertThat(quick.mirrored(city)).as("expired by the application clock").isEmpty();
        assertThat(isMember(driver)).as("its key may still be there").isTrue();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (exists(driver) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertThat(quick.mirrored(city)).isEmpty();
        assertThat(isMember(driver)).isFalse();
    }

    @Test
    void positionsComeBackExactlyAsSent() {
        at(north(123.456));

        assertThat(index.nearby(city, MINI, HERE, 1_000, 1)).singleElement()
                .satisfies(candidate -> assertThat(candidate.position()).isEqualTo(north(123.456)));
    }

    @Test
    void goingOfflineLeavesEverySetButTheDriverList() {
        UUID driver = at(north(100));

        index.mirror(city, driver, new MirrorState(Status.OFFLINE, 2, null, null));

        assertThat(score("geo:" + MINI, driver)).isNull();
        assertThat(score("geo:online", driver)).isNull();
        assertThat(score("seen", driver)).isNull();
        assertThat(isMember(driver)).isTrue();
    }

    @Test
    void aDriverBackFromATombstoneIsKeptForGood() throws InterruptedException {
        LiveIndex quick = new ValkeyLiveIndex(valkey, clock, FRESHNESS, Duration.ofMillis(100));
        UUID driver = Ids.newId();
        quick.mirror(city, driver, new MirrorState(Status.OFFLINE, 2, null, null));
        quick.mirror(city, driver, new MirrorState(Status.AVAILABLE, 3, MINI, null));

        Thread.sleep(300);

        assertThat(quick.mirrored(city)).containsEntry(driver, new MirrorState(Status.AVAILABLE, 3, MINI, null));
    }

    @Test
    void aCategoryChangedDuringAMirrorWriteIsReadAgain() {
        UUID driver = at(north(100));
        AtomicInteger runs = new AtomicInteger();
        AtomicBoolean interfered = new AtomicBoolean();
        LiveIndex racing = new ValkeyLiveIndex(new DelegatingValkey(valkey) {
            @Override
            public <T> T run(Script script, ScriptOutputType output, Duration timeout, String[] keys,
                    String... args) {
                if (script == ValkeyLiveIndex.MIRROR) {
                    runs.incrementAndGet();
                    if (interfered.compareAndSet(false, true)) {
                        // Another write lands between this one's read of the category and its script.
                        index.mirror(city, driver, new MirrorState(Status.AVAILABLE, 2, SEDAN, null));
                    }
                }
                return super.run(script, output, timeout, keys, args);
            }
        }, clock, FRESHNESS, TOMBSTONE_TTL);

        assertThat(racing.mirror(city, driver, new MirrorState(Status.OFFLINE, 3, null, null))).isTrue();

        assertThat(runs).hasValue(2);
        assertThat(score("geo:" + SEDAN, driver)).as("left the category it had changed to").isNull();
        assertThat(index.mirrored(city)).containsEntry(driver, new MirrorState(Status.OFFLINE, 3, null, null));
    }

    private Double score(String key, UUID driver) {
        return valkey.await("test", Valkeys.TIMEOUTS.other(),
                valkey.commands().zscore("{" + city + "}:" + key, driver.toString()));
    }

    private boolean isMember(UUID driver) {
        return valkey.await("test", Valkeys.TIMEOUTS.other(),
                valkey.commands().sismember("{" + city + "}:drivers", driver.toString()));
    }

    private boolean exists(UUID driver) {
        return valkey.await("test", Valkeys.TIMEOUTS.other(),
                valkey.commands().exists("{" + city + "}:drv:" + driver)) > 0;
    }

    /** The real Valkey, for a test to step in on one kind of call. */
    private static class DelegatingValkey implements Valkey {

        private final Valkey delegate;

        DelegatingValkey(Valkey delegate) {
            this.delegate = delegate;
        }

        @Override
        public RedisClusterAsyncCommands<String, String> commands() {
            return delegate.commands();
        }

        @Override
        public Timeouts timeouts() {
            return delegate.timeouts();
        }

        @Override
        public void load(Script... scripts) {
            delegate.load(scripts);
        }

        @Override
        public <T> T run(Script script, ScriptOutputType output, Duration timeout, String[] keys, String... args) {
            return delegate.run(script, output, timeout, keys, args);
        }

        @Override
        public <T> List<T> await(String operation, Duration timeout, List<RedisFuture<T>> futures) {
            return delegate.await(operation, timeout, futures);
        }
    }
}
