package com.ridehailing.platform.jobs;

import com.ridehailing.platform.Poller;
import com.ridehailing.platform.Role;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code outbox_oldest_unpublished_seconds} and {@code timers_overdue_seconds} (LLD §16.1), refreshed on every worker
 * node: a poller that never finds work, so it runs once per interval. Registered by the first refresh, so only nodes
 * that refresh them export them.
 */
@Component
class PlatformGauges implements Poller {

    static final String NAME = "platform-gauges";
    static final Duration INTERVAL = Duration.ofSeconds(5);

    private final JdbcClient jdbc;
    private final MeterRegistry meters;
    private final AtomicLong outboxLagMs = new AtomicLong();
    private final AtomicLong timersOverdueMs = new AtomicLong();
    private volatile boolean registered;

    PlatformGauges(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Role role() {
        return Role.WORKER;
    }

    @Override
    public int threads() {
        return 1;
    }

    @Override
    public Duration interval() {
        return INTERVAL;
    }

    @Override
    public boolean poll() {
        outboxLagMs.set(millis("""
                SELECT COALESCE(CAST(EXTRACT(EPOCH FROM now() - min(occurred_at)) * 1000 AS bigint), 0)
                FROM platform.outbox WHERE published_at IS NULL
                """));
        timersOverdueMs.set(millis("""
                SELECT COALESCE(CAST(EXTRACT(EPOCH FROM now() - min(due_at)) * 1000 AS bigint), 0)
                FROM platform.timers WHERE due_at <= now() AND parked_at IS NULL
                """));
        if (!registered) {
            Gauge.builder("outbox.oldest.unpublished.seconds", outboxLagMs, ms -> ms.get() / 1000.0)
                    .register(meters);
            Gauge.builder("timers.overdue.seconds", timersOverdueMs, ms -> ms.get() / 1000.0).register(meters);
            registered = true;
        }
        return false;
    }

    /** 0 when nothing is waiting. */
    private long millis(String sql) {
        return Math.max(0, jdbc.sql(sql).query(Long.class).single());
    }
}
