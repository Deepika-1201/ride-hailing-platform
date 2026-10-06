package com.ridehailing.ride.app;

import com.ridehailing.platform.LabelledGauges;
import com.ridehailing.platform.Poller;
import com.ridehailing.platform.Role;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code active_rides{city,category,status}} (LLD §16.1), refreshed on every worker node by a poller that never finds
 * work.
 */
@Component
class RideGauges implements Poller {

    static final String NAME = "ride-gauges";
    static final Duration INTERVAL = Duration.ofSeconds(5);

    private final JdbcClient jdbc;
    private final LabelledGauges activeRides;

    RideGauges(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.activeRides = new LabelledGauges(meters, "active.rides", List.of("city", "category", "status"));
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
        Map<List<String>, Long> active = new HashMap<>();
        jdbc.sql("""
                        SELECT city_id, category, status, count(*) AS rides FROM ride.rides
                        WHERE status IN ('SEARCHING', 'DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_TRIP')
                        GROUP BY city_id, category, status
                        """)
                .query(row -> {
                    active.put(List.of(row.getString("city_id"), row.getString("category"), row.getString("status")),
                            row.getLong("rides"));
                });
        activeRides.set(active);
        return false;
    }
}
