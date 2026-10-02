package com.ridehailing.support;

import java.time.Duration;

/** Retries an assertion until it passes or the timeout ends, for work done by background threads. */
public final class Eventually {

    private Eventually() {
    }

    public static void within(Duration timeout, Runnable assertion) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
