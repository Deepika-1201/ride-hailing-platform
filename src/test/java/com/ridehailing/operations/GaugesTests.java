package com.ridehailing.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.Poller;
import com.ridehailing.platform.Role;
import com.ridehailing.ride.RideView;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestRides.AssignedRide;
import com.ridehailing.support.TestUsers.TestUser;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** LLD §16.1: the gauges every worker node refreshes, read back from the registry and the scrape. */
class GaugesTests extends IntegrationTest {

    @Autowired
    TestCities cities;

    @Autowired
    TestPrices prices;

    @Autowired
    TestRides rides;

    @Autowired
    ObjectProvider<Poller> pollers;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcClient jdbc;

    @Test
    void liveDriversAndActiveRidesAreCountedPerCityCategoryAndStatusAndFallToZero() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        TestDriver idle = rides.onlineAt(city, "MINI", city.at(0.4, 0.4));
        AssignedRide assigned = rides.assigned(city, city.at(0.1, 0.1), city.at(0.1, 0.1));
        TestUser rider = rides.rider("Rider");
        RideView searching = rides.book(rider.id(), city, city.at(0.3, 0.3), "MINI");

        refresh();

        assertThat(gauge("live.drivers", city, "AVAILABLE")).isEqualTo(1);
        assertThat(gauge("live.drivers", city, "ASSIGNED")).isEqualTo(1);
        assertThat(gauge("active.rides", city, "DRIVER_ASSIGNED")).isEqualTo(1);
        assertThat(gauge("active.rides", city, "SEARCHING")).isEqualTo(1);
        assertThat(get(managementPort, "/actuator/prometheus").body())
                .contains("live_drivers{category=\"MINI\",city=\"" + city.id() + "\",status=\"ASSIGNED\"} 1.0")
                .contains("active_rides{category=\"MINI\",city=\"" + city.id() + "\",status=\"SEARCHING\"} 1.0");

        assertThat(postJson("/v1/drivers/me/offline", Map.of("Authorization", idle.authorization(),
                Idempotency.HEADER, UUID.randomUUID().toString()), "{}").statusCode()).isEqualTo(200);
        assertThat(postJson("/v1/rides/" + searching.id() + "/cancel", Map.of("Authorization", rider.authorization(),
                Idempotency.HEADER, UUID.randomUUID().toString()), "{}").statusCode()).isEqualTo(200);
        refresh();

        assertThat(gauge("live.drivers", city, "AVAILABLE")).as("no longer online").isZero();
        assertThat(gauge("active.rides", city, "SEARCHING")).as("no longer active").isZero();
        assertThat(gauge("live.drivers", city, "ASSIGNED")).isEqualTo(1);
        assertThat(gauge("live.drivers", city, "OFFLINE")).as("offline drivers aren't live").isEqualTo(-1);
        assertThat(gauge("active.rides", city, "CANCELLED_BY_RIDER")).as("ended rides aren't active").isEqualTo(-1);
        assertThat(rides.rideStatus(assigned.id())).isEqualTo("DRIVER_ASSIGNED");
    }

    @Test
    void theOldestUnpublishedEventTheMostOverdueTimerAndDueTasksAreExported() {
        probeEvent("now() - interval '10 minutes'", "NULL");
        probeEvent("now() - interval '2 days'", "now()");
        UUID timer = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO platform.timers (kind, aggregate_id, due_at) VALUES ('GAUGE_PROBE', :id,
                                                                                now() - interval '5 minutes')
                """).param("id", timer).update();
        jdbc.sql("""
                INSERT INTO platform.timers (kind, aggregate_id, due_at, parked_at)
                VALUES ('GAUGE_PROBE', :id, now() - interval '2 days', now())
                """).param("id", UUID.randomUUID()).update();
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        RideView searching = rides.book(rides.rider("Rider").id(), city, city.at(0.1, 0.1), "MINI");
        rides.onlyDueIn(city.id());
        try {
            refresh();

            assertThat(meters.get("outbox.oldest.unpublished.seconds").gauge().value())
                    .as("the unpublished probe, not the published one")
                    .isBetween((double) Duration.ofMinutes(10).toSeconds(), (double) Duration.ofDays(1).toSeconds());
            assertThat(meters.get("timers.overdue.seconds").gauge().value()).as("not the parked timer")
                    .isBetween((double) Duration.ofMinutes(5).toSeconds(), (double) Duration.ofDays(1).toSeconds());
            assertThat(meters.get("search.tasks.due").gauge().value()).as("this city's task; the others wait")
                    .isEqualTo(1);
            assertThat(get(managementPort, "/actuator/prometheus").body()).contains("outbox_oldest_unpublished_seconds ",
                    "timers_overdue_seconds ", "search_tasks_due ");
            assertThat(searching.status().name()).isEqualTo("SEARCHING");
        } finally {
            jdbc.sql("UPDATE platform.outbox SET published_at = now() WHERE event_type = 'GaugeProbe'").update();
            jdbc.sql("DELETE FROM platform.timers WHERE kind = 'GAUGE_PROBE'").update();
        }
    }

    @Test
    void workerNodesRefreshTheGaugesEveryFiveSeconds() {
        for (String name : List.of("platform-gauges", "dispatch-gauges", "ride-gauges")) {
            Poller poller = poller(name);

            assertThat(poller.role()).as(name).isEqualTo(Role.WORKER);
            assertThat(poller.threads()).as(name).isEqualTo(1);
            assertThat(poller.interval()).as(name).isEqualTo(Duration.ofSeconds(5));
            assertThat(poller.poll()).as("%s never finds work, so it waits a whole interval", name).isFalse();
        }
    }

    private void refresh() {
        List.of("platform-gauges", "dispatch-gauges", "ride-gauges").forEach(name -> poller(name).poll());
    }

    /** An event of no consumer's type; both times are SQL. */
    private void probeEvent(String occurredAt, String publishedAt) {
        jdbc.sql("INSERT INTO platform.outbox (event_id, event_type, event_version, aggregate_type, aggregate_id,"
                + " aggregate_version, partition_key, occurred_at, producer, correlation_id, payload, published_at)"
                + " VALUES (gen_random_uuid(), 'GaugeProbe', 1, 'probe', gen_random_uuid(), 0, gen_random_uuid(), "
                + occurredAt + ", 'test', 'probe', '{}', " + publishedAt + ")").update();
    }

    private Poller poller(String name) {
        return pollers.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
    }

    private double gauge(String name, TestCity city, String status) {
        Gauge gauge = meters.find(name).tags("city", city.id(), "category", "MINI", "status", status).gauge();
        return gauge == null ? -1 : gauge.value();
    }
}
