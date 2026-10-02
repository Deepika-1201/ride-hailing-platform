package com.ridehailing.platform;

import java.util.function.Supplier;

/** Runs work in a transaction, retrying deadlocks and serialization failures (LLD §1.4). */
public interface Transactions {

    /**
     * Runs {@code work} in a new transaction, or in the caller's if one is active. A transaction that this call started
     * is run again, up to 3 times, after a deadlock ({@code 40P01}) or serialization failure ({@code 40001}), so
     * {@code work} must be safe to repeat after a rollback.
     */
    <T> T execute(Supplier<T> work);

    default void run(Runnable work) {
        execute(() -> {
            work.run();
            return null;
        });
    }
}
