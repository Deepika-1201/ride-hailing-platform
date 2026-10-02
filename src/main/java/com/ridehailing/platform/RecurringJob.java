package com.ridehailing.platform;

import java.time.Duration;

/**
 * Work that runs once per interval across the cluster, on a node with the given role, under the lease
 * {@code job:<name>} (LLD §5.7). Runs may overlap if one outlasts its interval, so jobs are idempotent.
 */
public interface RecurringJob {

    String name();

    Role role();

    Duration interval();

    /** Runs outside any transaction; a failure is logged and the job waits for its next interval. */
    void run();
}
