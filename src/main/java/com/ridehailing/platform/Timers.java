package com.ridehailing.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable timers in the database (ADR-005, LLD §5.4). */
public interface Timers {

    /** Schedules a timer in the caller's transaction; it fires at least once at or after {@code dueAt}. */
    void schedule(TimerKind kind, UUID aggregateId, Instant dueAt, Map<String, Object> payload);

    /** As {@link #schedule}, due {@code delay} after the database's {@code now()}, so no application time is mixed in. */
    void scheduleAfter(TimerKind kind, UUID aggregateId, Duration delay, Map<String, Object> payload);

    /** Which of the aggregates have a timer of this kind, for invariant checks (LLD §17.3). */
    Set<UUID> scheduled(TimerKind kind, Collection<UUID> aggregateIds);

    /**
     * Removes the aggregate's timers of this kind in the caller's transaction, without waiting: a timer that is firing
     * right now is skipped, and its handler finds the state moved on (LLD §6.2).
     *
     * @return how many timers were removed
     */
    int cancel(TimerKind kind, UUID aggregateId);
}
