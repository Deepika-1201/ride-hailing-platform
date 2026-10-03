package com.ridehailing.platform.tx;

import com.ridehailing.platform.Transactions;
import java.sql.SQLException;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class RetryingTransactions implements Transactions {

    static final int MAX_RETRIES = 3;

    private static final Logger log = LoggerFactory.getLogger(RetryingTransactions.class);
    private static final Set<String> RETRYABLE_STATES = Set.of("40P01", "40001");

    private final TransactionTemplate template;

    RetryingTransactions(TransactionTemplate template) {
        this.template = template;
    }

    @Override
    public <T> T execute(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // A participant can't retry alone: the transaction's owner does.
            return work.get();
        }
        for (int retry = 0; ; retry++) {
            try {
                return template.execute(status -> work.get());
            } catch (RuntimeException e) {
                String state = retryableState(e);
                if (state == null || retry == MAX_RETRIES) {
                    throw e;
                }
                log.warn("Transaction failed with SQL state {}; retrying ({} of {})", state, retry + 1, MAX_RETRIES);
            }
        }
    }

    @Override
    public void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static String retryableState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && RETRYABLE_STATES.contains(sql.getSQLState())) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
