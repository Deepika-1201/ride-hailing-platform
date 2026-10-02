package com.ridehailing.platform;

/**
 * Fires timers of one kind (LLD §5.4). {@code fire} runs in the transaction that claimed the timer, which also
 * deletes it; if it throws, the timer is retried with backoff. Firing is at least once, so handlers re-check state.
 */
public interface TimerHandler {

    TimerKind kind();

    void fire(DueTimer timer);
}
