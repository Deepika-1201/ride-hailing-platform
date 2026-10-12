package com.ridehailing.platform.leases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ridehailing.platform.Leases;
import com.ridehailing.support.Effects;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.PlatformTables;
import com.ridehailing.support.Postgis;
import java.sql.Connection;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** LLD §5.5: one holder at a time, takeover after expiry, and a fencing token that grows with every acquisition. */
class LeasesTests extends IntegrationTest {

    private static final String LEASE = "test-lease";
    private static final Duration TTL = Duration.ofSeconds(10);

    @Autowired
    private Leases leases;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void reset() {
        PlatformTables.reset(jdbc);
        Effects.reset(jdbc);
    }

    @Test
    void theFirstAcquisitionCreatesTheLeaseWithToken1() {
        assertThat(leases.acquire(LEASE, "node-a", TTL)).hasValue(1);
    }

    @Test
    void aHeldLeaseCannotBeTakenEvenByItsHolder() {
        leases.acquire(LEASE, "node-a", TTL);

        assertThat(leases.acquire(LEASE, "node-b", TTL)).isEmpty();
        assertThat(leases.acquire(LEASE, "node-a", TTL)).isEmpty();
    }

    @Test
    void theHolderRenewsWithItsToken() {
        long token = leases.acquire(LEASE, "node-a", TTL).orElseThrow();

        assertThat(leases.renew(LEASE, "node-a", token, TTL)).isTrue();
        assertThat(leases.renew(LEASE, "node-b", token, TTL)).isFalse();
        assertThat(leases.renew(LEASE, "node-a", token + 1, TTL)).isFalse();
    }

    @Test
    void anExpiredLeaseIsTakenOverWithANewTokenAndTheOldHolderCannotRenew() {
        long first = leases.acquire(LEASE, "node-a", TTL).orElseThrow();
        expire(LEASE);

        OptionalLong second = leases.acquire(LEASE, "node-b", TTL);

        assertThat(second).hasValue(first + 1);
        assertThat(leases.renew(LEASE, "node-a", first, TTL)).isFalse();
        assertThat(leases.renew(LEASE, "node-b", second.getAsLong(), TTL)).isTrue();
    }

    @Test
    void anExpiredLeaseCannotBeRenewedEvenWithoutATakeover() {
        long token = leases.acquire(LEASE, "node-a", TTL).orElseThrow();
        expire(LEASE);

        assertThat(leases.renew(LEASE, "node-a", token, TTL)).isFalse();
    }

    @Test
    void aReleasedLeaseIsFreeAtOnce() {
        long token = leases.acquire(LEASE, "node-a", TTL).orElseThrow();
        leases.release(LEASE, "node-b", token);
        assertThat(leases.acquire(LEASE, "node-b", TTL)).isEmpty();

        leases.release(LEASE, "node-a", token);

        assertThat(leases.acquire(LEASE, "node-b", TTL)).hasValue(token + 1);
    }

    @Test
    void guardedWorkCommitsOnlyForTheCurrentHolderAndToken() {
        long token = leases.acquire(LEASE, "node-a", TTL).orElseThrow();
        Runnable effect = () -> Effects.record(jdbc, "lease-guard", "effect");

        assertThat(leases.runIfHeld("missing", "node-a", token, effect)).isFalse();
        assertThat(leases.runIfHeld(LEASE, "node-b", token, effect)).isFalse();
        assertThat(leases.runIfHeld(LEASE, "node-a", token + 1, effect)).isFalse();
        assertThat(Effects.refs(jdbc, "lease-guard")).isEmpty();
        assertThat(leases.runIfHeld(LEASE, "node-a", token, effect)).isTrue();
        assertThat(Effects.refs(jdbc, "lease-guard")).containsExactly("effect");
        expire(LEASE);
        long next = leases.acquire(LEASE, "node-a", TTL).orElseThrow();
        assertThat(next).isGreaterThan(token);
        assertThat(leases.runIfHeld(LEASE, "node-a", token, effect)).isFalse();
    }

    @Test
    void anExpiredLeaseAndAnExpiryDuringWorkCannotCommitEffects() {
        long token = leases.acquire(LEASE, "node-a", TTL).orElseThrow();
        assertThat(leases.runIfHeld(LEASE, "node-a", token, () -> {
            Effects.record(jdbc, "lease-guard", "rolled-back");
            expire(LEASE);
        })).isFalse();
        assertThat(Effects.refs(jdbc, "lease-guard")).isEmpty();
        expire(LEASE);
        assertThat(leases.runIfHeld(LEASE, "node-a", token, () -> {
            throw new AssertionError("expired owner ran work");
        })).isFalse();
    }

    @Test
    void failuresRollBackAndAnOuterTransactionIsRejected() {
        long token = leases.acquire(LEASE, "node-a", TTL).orElseThrow();
        assertThatThrownBy(() -> leases.runIfHeld(LEASE, "node-a", token, () -> {
            Effects.record(jdbc, "lease-guard", "failed");
            throw new IllegalStateException("work failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("work failed");
        assertThat(Effects.refs(jdbc, "lease-guard")).isEmpty();
        assertThatThrownBy(() -> transactions.executeWithoutResult(transaction ->
                leases.runIfHeld(LEASE, "node-a", token, () -> { })))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("own its transaction");
    }

    @Test
    void aGuardWaitingForTheLeaseLockChecksTheCurrentDatabaseTime() throws Exception {
        long token = leases.acquire(LEASE, "node-a", TTL).orElseThrow();
        try (Connection gate = Postgis.connection(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            gate.setAutoCommit(false);
            gate.createStatement().execute("SELECT name FROM platform.leases WHERE name = 'test-lease' FOR UPDATE");
            var guarded = executor.submit(() -> leases.runIfHeld(LEASE, "node-a", token,
                    () -> Effects.record(jdbc, "lease-guard", "late")));
            try {
                await().atMost(Duration.ofSeconds(3)).until(() -> jdbc.sql("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'
                        """).query(Long.class).single() > 0);
                gate.createStatement().execute("UPDATE platform.leases SET expires_at = clock_timestamp() WHERE name = 'test-lease'");
                gate.commit();
                assertThat(guarded.get(5, TimeUnit.SECONDS)).isFalse();
            } finally {
                gate.rollback();
            }
        }
        assertThat(Effects.refs(jdbc, "lease-guard")).isEmpty();
    }

    @Test
    void aSuccessorWaitsForExpiredGuardedWorkToRollBack() throws Exception {
        long token = leases.acquire(LEASE, "node-a", Duration.ofSeconds(2)).orElseThrow();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var guarded = executor.submit(() -> leases.runIfHeld(LEASE, "node-a", token, () -> {
                Effects.record(jdbc, "lease-guard", "expired-owner");
                entered.countDown();
                try {
                    assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.sql("""
                        SELECT expires_at <= clock_timestamp() FROM platform.leases WHERE name = :name
                        """).param("name", LEASE).query(Boolean.class).single());
                var successor = executor.submit(() -> leases.acquire(LEASE, "node-b", TTL));
                await().atMost(Duration.ofSeconds(3)).until(() -> jdbc.sql("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'
                          AND query LIKE '%platform.leases%'
                        """).query(Long.class).single() > 0);
                assertThat(successor.isDone()).isFalse();
                finish.countDown();
                assertThat(guarded.get(5, TimeUnit.SECONDS)).isFalse();
                assertThat(successor.get(5, TimeUnit.SECONDS).orElseThrow()).isGreaterThan(token);
            } finally {
                finish.countDown();
            }
        }
        assertThat(Effects.refs(jdbc, "lease-guard")).isEmpty();
    }

    private void expire(String lease) {
        jdbc.sql("UPDATE platform.leases SET expires_at = now() - interval '1 second' WHERE name = :name")
                .param("name", lease)
                .update();
    }
}
