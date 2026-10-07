package com.ridehailing.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.Candidate;
import com.ridehailing.platform.LogContext;
import com.ridehailing.platform.RecurringJob;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.ValkeyIntegrationTest;
import com.ridehailing.support.Valkeys;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * NFR-7 (LLD §8.10, §17.1): after Valkey loses everything, matching comes back within 10 s with no one's help. The
 * drivers' apps keep sending every 4 s and the watch job runs every 2 s, as in production; the test only plays both.
 */
class ValkeyRecoveryTests extends ValkeyIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ValkeyRecoveryTests.class);
    private static final String LOCATION = "/v1/drivers/me/location";
    private static final int DRIVERS = 5;
    private static final Duration APP_INTERVAL = Duration.ofSeconds(4);

    @Autowired
    private TestCities cities;

    @Autowired
    private TestDrivers drivers;

    @Autowired
    private DispatchApi dispatch;

    @Autowired
    private LiveIndex index;

    @Autowired
    private List<RecurringJob> jobs;

    private final AtomicLong seq = new AtomicLong();

    @Test
    void matchingComesBackWithinTenSecondsAfterValkeyLosesEverything() throws Exception {
        TestCity city = cities.create("MINI");
        GeoPoint pickup = city.at(0.2, 0.2);
        List<TestDriver> online = IntStream.range(0, DRIVERS).mapToObj(i -> drivers.create(city, "MINI")).toList();
        for (int i = 0; i < DRIVERS; i++) {
            TestDriver driver = online.get(i);
            LogContext.run(Map.of(LogContext.ROLE, "api"), () -> dispatch.goOnline(driver.id(), driver.vehicleId()));
            assertThat(send(driver, position(city, i)).body()).contains("\"applied\":1");
        }
        assertThat(candidates(city, pickup)).hasSize(DRIVERS);
        RecurringJob watch = jobs.stream().filter(job -> job.name().equals("live-index-watch")).findFirst()
                .orElseThrow();
        assertThat(watch.interval()).isEqualTo(Duration.ofSeconds(2));

        Valkeys.standalone().await("test", Valkeys.TIMEOUTS.other(), Valkeys.standalone().commands().flushall());
        long lost = System.nanoTime();
        assertThat(candidates(city, pickup)).isEmpty();

        ScheduledExecutorService timeline = Executors.newScheduledThreadPool(2);
        try {
            for (int i = 0; i < DRIVERS; i++) {
                TestDriver driver = online.get(i);
                GeoPoint at = position(city, i);
                // Each app sends at its own moment in the 4 s cycle.
                timeline.scheduleAtFixedRate(() -> send(driver, at), i * APP_INTERVAL.toMillis() / DRIVERS,
                        APP_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
            }
            // The worst case: the watch ran just before the loss, so its next run is a whole interval later.
            timeline.scheduleAtFixedRate(watch::run, watch.interval().toMillis(), watch.interval().toMillis(),
                    TimeUnit.MILLISECONDS);
            while (candidates(city, pickup).size() < DRIVERS
                    && Duration.ofNanos(System.nanoTime() - lost).compareTo(Duration.ofSeconds(15)) < 0) {
                Thread.sleep(50);
            }
        } finally {
            timeline.shutdownNow();
        }

        Duration recovery = Duration.ofNanos(System.nanoTime() - lost);
        log.info("Matching came back {} ms after Valkey lost everything", recovery.toMillis());
        assertThat(candidates(city, pickup)).hasSize(DRIVERS);
        assertThat(recovery).isLessThan(Duration.ofSeconds(10));
    }

    private List<Candidate> candidates(TestCity city, GeoPoint pickup) {
        return index.nearby(city.id(), "MINI", pickup, 5_000, 10);
    }

    /** About 100 m apart, a few hundred metres from the pickup. */
    private static GeoPoint position(TestCity city, int driver) {
        return city.at(0.2 + driver * 0.001, 0.2);
    }

    private HttpResponse<String> send(TestDriver driver, GeoPoint at) {
        return call("POST", driver.authorization(), LOCATION, """
                {"updates": [{"seq": %d, "lat": %s, "lon": %s, "accuracy_m": 5, "device_time": "%s"}]}"""
                .formatted(seq.incrementAndGet(), at.lat(), at.lon(), Instant.now()));
    }
}
