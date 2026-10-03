package com.ridehailing.platform.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.platform.Transactions;
import com.ridehailing.support.IntegrationTest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** LLD §1.4: deadlocks and serialization failures are retried up to 3 times by the transaction's owner only. */
class TransactionsTests extends IntegrationTest {

    @Autowired
    private Transactions transactions;

    @Autowired
    private TransactionTemplate template;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void accounts() {
        jdbc.sql("CREATE SCHEMA IF NOT EXISTS test_support").update();
        jdbc.sql("CREATE TABLE IF NOT EXISTS test_support.accounts (id int PRIMARY KEY, balance int NOT NULL)").update();
        jdbc.sql("TRUNCATE test_support.accounts").update();
        jdbc.sql("INSERT INTO test_support.accounts VALUES (1, 100), (2, 100)").update();
    }

    @Test
    void retriesTheVictimOfARealDeadlock() throws Exception {
        CyclicBarrier bothHoldTheirFirstRow = new CyclicBarrier(2);
        AtomicInteger attempts = new AtomicInteger();
        List<Future<?>> transfers = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int[] route : new int[][] {{1, 2}, {2, 1}}) {
                transfers.add(executor.submit(() -> {
                    boolean[] firstAttempt = {true};
                    transactions.run(() -> {
                        attempts.incrementAndGet();
                        move(route[0], -10);
                        if (firstAttempt[0]) {
                            firstAttempt[0] = false;
                            await(bothHoldTheirFirstRow);
                        }
                        move(route[1], 10);
                    });
                }));
            }
            for (Future<?> transfer : transfers) {
                transfer.get(20, TimeUnit.SECONDS);
            }
        }

        assertThat(attempts).hasValue(3);
        assertThat(jdbc.sql("SELECT sum(balance) FROM test_support.accounts").query(Long.class).single())
                .isEqualTo(200L);
        assertThat(jdbc.sql("SELECT balance FROM test_support.accounts WHERE id = 1").query(Integer.class).single())
                .isEqualTo(100);
    }

    @Test
    void givesUpAfterThreeRetries() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> transactions.run(() -> {
            attempts.incrementAndGet();
            throw serializationFailure();
        })).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(attempts).hasValue(1 + RetryingTransactions.MAX_RETRIES);
    }

    @Test
    void doesNotRetryOtherFailures() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> transactions.run(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("a bug, not contention");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void leavesRetryingToTheTransactionsOwner() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> template.executeWithoutResult(status -> transactions.run(() -> {
            attempts.incrementAndGet();
            throw serializationFailure();
        }))).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void everyPooledConnectionHasTheStatementTimeout() {
        assertThat(jdbc.sql("SHOW statement_timeout").query(String.class).single()).isEqualTo("2s");
    }

    @Test
    void afterCommitRunsOnceTheTransactionCommits() {
        List<String> ran = new ArrayList<>();

        transactions.run(() -> {
            transactions.afterCommit(() -> ran.add("after commit"));
            assertThat(ran).as("not before the commit").isEmpty();
        });

        assertThat(ran).containsExactly("after commit");
    }

    @Test
    void afterCommitNeverRunsOnARollback() {
        List<String> ran = new ArrayList<>();

        assertThatThrownBy(() -> transactions.run(() -> {
            transactions.afterCommit(() -> ran.add("after commit"));
            throw new IllegalStateException("rolled back");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(ran).isEmpty();
    }

    @Test
    void afterCommitRunsOnceForARetriedTransaction() {
        AtomicInteger attempts = new AtomicInteger();
        List<String> ran = new ArrayList<>();

        transactions.run(() -> {
            int attempt = attempts.incrementAndGet();
            transactions.afterCommit(() -> ran.add("attempt " + attempt));
            if (attempt == 1) {
                throw serializationFailure();
            }
        });

        assertThat(ran).as("only the attempt that committed").containsExactly("attempt 2");
    }

    @Test
    void afterCommitRunsAtOnceWithoutATransaction() {
        List<String> ran = new ArrayList<>();

        transactions.afterCommit(() -> ran.add("now"));

        assertThat(ran).containsExactly("now");
    }

    private void move(int account, int amount) {
        jdbc.sql("UPDATE test_support.accounts SET balance = balance + :amount WHERE id = :id")
                .param("amount", amount)
                .param("id", account)
                .update();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PessimisticLockingFailureException serializationFailure() {
        return new PessimisticLockingFailureException("simulated",
                new SQLException("could not serialize access", "40001"));
    }
}
