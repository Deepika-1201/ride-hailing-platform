package com.ridehailing.platform.timers;

import static com.ridehailing.support.ScriptedTimerHandler.Kind.TEST_TIMER;
import static com.ridehailing.support.ScriptedTimerHandler.Kind.UNHANDLED_TIMER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.platform.Timers;
import com.ridehailing.support.Effects;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.PlatformTables;
import com.ridehailing.support.ScriptedTimerHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** LLD §5.4, §6.2: timers fire at least once, survive failed firings, park when hopeless, and cancel without waiting. */
class TimersTests extends IntegrationTest {

    // Far from now, so a clock difference between the host and the database VM can't matter.
    private static final Instant PAST = Instant.now().minus(Duration.ofHours(1));
    private static final Instant FUTURE = Instant.now().plus(Duration.ofHours(1));

    @Autowired
    private Timers timers;

    @Autowired
    private TimerPoller poller;

    @Autowired
    private TimerProperties properties;

    @Autowired
    private ScriptedTimerHandler timerHandler;

    @Autowired
    private TransactionTemplate template;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        PlatformTables.reset(jdbc);
        Effects.reset(jdbc);
        timerHandler.reset();
    }

    @Test
    void schedulingNeedsATransaction() {
        assertThatThrownBy(() -> timers.schedule(TEST_TIMER, UUID.randomUUID(), PAST, Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aRolledBackScheduleLeavesNoTimer() {
        template.executeWithoutResult(status -> {
            timers.schedule(TEST_TIMER, UUID.randomUUID(), PAST, Map.of());
            status.setRollbackOnly();
        });

        assertThat(timerCount()).isZero();
    }

    @Test
    void aDueTimerFiresOnceWithItsPayloadAndIsDeleted() {
        UUID ride = schedule(TEST_TIMER, PAST);
        List<String> generations = new ArrayList<>();
        timerHandler.afterEffect(timer -> generations.add(timer.payload().get("search_generation").asString()));

        assertThat(poller.fireNext()).isTrue();
        assertThat(poller.fireNext()).isFalse();

        assertThat(Effects.count(jdbc, ScriptedTimerHandler.SOURCE, ride.toString())).isEqualTo(1);
        assertThat(generations).containsExactly("2");
        assertThat(timerCount()).isZero();
    }

    @Test
    void aTimerNotYetDueWaits() {
        schedule(TEST_TIMER, FUTURE);

        assertThat(poller.fireNext()).isFalse();
        assertThat(timerCount()).isEqualTo(1);
    }

    @Test
    void aTimerFiresAgainAfterAFailedFiringWasRolledBack() {
        UUID ride = schedule(TEST_TIMER, PAST);
        timerHandler.script.failTimes(ride, 1);

        assertThat(poller.fireNext()).isTrue();

        assertThat(Effects.count(jdbc, ScriptedTimerHandler.SOURCE, ride.toString())).isZero();
        Map<String, Object> retry = jdbc.sql("""
                        SELECT attempts, last_error, due_at > now() + interval '1 second' AS backed_off
                        FROM platform.timers
                        """)
                .query().singleRow();
        assertThat(retry).containsEntry("attempts", 1).containsEntry("backed_off", true);
        assertThat((String) retry.get("last_error")).contains("Scripted failure");

        makeDue();
        assertThat(poller.fireNext()).isTrue();

        assertThat(Effects.count(jdbc, ScriptedTimerHandler.SOURCE, ride.toString())).isEqualTo(1);
        assertThat(timerCount()).isZero();
    }

    @Test
    void aTimerThatKeepsFailingIsParkedAndNoLongerClaimed() {
        UUID ride = schedule(TEST_TIMER, PAST);
        timerHandler.script.failAlways(ride);

        for (int failure = 0; failure < properties.maxFailures(); failure++) {
            makeDue();
            assertThat(poller.fireNext()).isTrue();
        }
        makeDue();

        assertThat(poller.fireNext()).isFalse();
        Map<String, Object> parked = jdbc.sql("SELECT attempts, parked_at IS NOT NULL AS parked FROM platform.timers")
                .query().singleRow();
        assertThat(parked).containsEntry("attempts", properties.maxFailures()).containsEntry("parked", true);
    }

    @Test
    void cancelRemovesOnlyThatAggregatesTimersOfThatKind() {
        UUID ride = UUID.randomUUID();
        template.executeWithoutResult(status -> {
            timers.schedule(TEST_TIMER, ride, FUTURE, Map.of());
            timers.schedule(TEST_TIMER, ride, FUTURE, Map.of());
            timers.schedule(UNHANDLED_TIMER, ride, FUTURE, Map.of());
            timers.schedule(TEST_TIMER, UUID.randomUUID(), FUTURE, Map.of());
        });

        Integer cancelled = template.execute(status -> timers.cancel(TEST_TIMER, ride));

        assertThat(cancelled).isEqualTo(2);
        assertThat(timerCount()).isEqualTo(2);
    }

    @Test
    void scheduledAnswersTheAggregatesWithATimerOfThatKind() {
        UUID both = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID none = UUID.randomUUID();
        template.executeWithoutResult(status -> {
            timers.schedule(TEST_TIMER, both, FUTURE, Map.of());
            timers.schedule(TEST_TIMER, both, FUTURE, Map.of());
            timers.schedule(UNHANDLED_TIMER, other, FUTURE, Map.of());
        });

        assertThat(timers.scheduled(TEST_TIMER, List.of(both, other, none))).containsExactly(both);
        assertThat(timers.scheduled(TEST_TIMER, List.of())).isEmpty();
    }

    @Test
    void scheduleAfterCountsFromTheDatabasesTime() {
        UUID ride = UUID.randomUUID();
        template.executeWithoutResult(status -> timers.scheduleAfter(TEST_TIMER, ride, Duration.ofMillis(90_500),
                Map.of("generation", 2)));

        assertThat(jdbc.sql("""
                        SELECT extract(epoch FROM due_at - created_at) || ' ' || (payload ->> 'generation')
                        FROM platform.timers WHERE aggregate_id = :id
                        """).param("id", ride).query(String.class).single()).isEqualTo("90.500000 2");
    }

    @Test
    void cancelNeverWaitsForATimerBeingFired() throws Exception {
        UUID ride = schedule(TEST_TIMER, PAST);
        CountDownLatch firing = new CountDownLatch(1);
        CountDownLatch finishFiring = new CountDownLatch(1);
        timerHandler.afterEffect(timer -> {
            firing.countDown();
            await(finishFiring);
        });
        CompletableFuture<Boolean> fired = CompletableFuture.supplyAsync(poller::fireNext);
        assertThat(firing.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        Integer cancelled = template.execute(status -> timers.cancel(TEST_TIMER, ride));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
        assertThat(cancelled).isZero();
        finishFiring.countDown();
        assertThat(fired.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(timerCount()).isZero();
    }

    @Test
    void kindsWithoutAHandlerInThisProcessAreLeftAlone() {
        schedule(UNHANDLED_TIMER, PAST);

        assertThat(poller.fireNext()).isFalse();
        assertThat(jdbc.sql("SELECT attempts FROM platform.timers").query(Integer.class).single()).isZero();
    }

    @Test
    void concurrentPollersFireEachTimerExactlyOnce() throws Exception {
        template.executeWithoutResult(status -> {
            for (int index = 0; index < 200; index++) {
                timers.schedule(TEST_TIMER, UUID.randomUUID(), PAST, Map.of());
            }
        });

        List<Future<?>> workers = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (int worker = 0; worker < 4; worker++) {
                workers.add(executor.submit(() -> {
                    while (poller.fireNext()) {
                        Thread.onSpinWait();
                    }
                }));
            }
            for (Future<?> worker : workers) {
                worker.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(timerCount()).isZero();
        assertThat(jdbc.sql("SELECT count(DISTINCT ref) FROM test_support.effects").query(Long.class).single())
                .isEqualTo(200L);
        assertThat(jdbc.sql("SELECT count(*) FROM test_support.effects").query(Long.class).single()).isEqualTo(200L);
    }

    private UUID schedule(ScriptedTimerHandler.Kind kind, Instant dueAt) {
        UUID ride = UUID.randomUUID();
        template.executeWithoutResult(status -> timers.schedule(kind, ride, dueAt, Map.of("search_generation", 2)));
        return ride;
    }

    private void makeDue() {
        jdbc.sql("UPDATE platform.timers SET due_at = now() - interval '1 second'").update();
    }

    private long timerCount() {
        return jdbc.sql("SELECT count(*) FROM platform.timers").query(Long.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
