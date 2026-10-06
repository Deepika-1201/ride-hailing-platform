package com.ridehailing.dispatch.app;

import com.ridehailing.platform.LabelledGauges;
import com.ridehailing.platform.Poller;
import com.ridehailing.platform.Role;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code live_drivers{city,category,status}} and {@code search_tasks_due} (LLD §16.1), refreshed on every worker node
 * by a poller that never finds work. Registered by the first refresh, so only nodes that refresh them export them.
 */
@Component
class DispatchGauges implements Poller {

    static final String NAME = "dispatch-gauges";
    static final Duration INTERVAL = Duration.ofSeconds(5);

    private final JdbcClient jdbc;
    private final MeterRegistry meters;
    private final LabelledGauges liveDrivers;
    private final AtomicLong tasksDue = new AtomicLong();
    private volatile boolean registered;

    DispatchGauges(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
        this.liveDrivers = new LabelledGauges(meters, "live.drivers", List.of("city", "category", "status"));
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
        Map<List<String>, Long> online = new HashMap<>();
        jdbc.sql("""
                        SELECT city_id, category, status, count(*) AS drivers FROM dispatch.driver_availability
                        WHERE status <> 'OFFLINE' GROUP BY city_id, category, status
                        """)
                .query(row -> {
                    online.put(List.of(row.getString("city_id"), row.getString("category"), row.getString("status")),
                            row.getLong("drivers"));
                });
        liveDrivers.set(online);
        tasksDue.set(jdbc.sql("SELECT count(*) FROM dispatch.search_tasks WHERE due_at <= clock_timestamp()")
                .query(Long.class).single());
        if (!registered) {
            Gauge.builder("search.tasks.due", tasksDue, AtomicLong::get).register(meters);
            registered = true;
        }
        return false;
    }
}
