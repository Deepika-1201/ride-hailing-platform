package com.ridehailing.platform;

import java.time.Duration;

/**
 * Work that a module polls for on threads of one role, such as due search tasks (LLD §5.7). Every process with the
 * role runs it; units of work are claimed with {@code SKIP LOCKED}, so nodes never take the same one.
 */
public interface Poller {

    String name();

    Role role();

    int threads();

    /** How long a thread that found nothing waits before polling again. */
    Duration interval();

    /** Handles at most one unit of work, in its own transaction; answers whether there was one. */
    boolean poll();
}
