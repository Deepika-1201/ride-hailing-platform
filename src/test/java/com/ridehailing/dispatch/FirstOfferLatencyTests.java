package com.ridehailing.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.shared.GeoPoint;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;

/**
 * Phase 7's exit criterion: booking to first offer under 2 s at p95, with the search-task poller running as in
 * production. Measured as the rider sees it, from the booking call to the offer being readable by the driver.
 */
class FirstOfferLatencyTests extends IntegrationTest {

    private static final int DRIVERS = 200;
    private static final int BOOKERS = 5;
    private static final int RIDES_PER_BOOKER = 10;
    private static final Duration LIMIT = Duration.ofSeconds(2);
    private static final Duration GIVE_UP = Duration.ofSeconds(10);

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestRides rides;

    @Autowired
    private ApplicationContext context;

    @Test
    void theFirstOfferArrivesWithinTwoSecondsAtP95() throws Exception {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        Random random = new Random(7);
        for (int driver = 0; driver < DRIVERS; driver++) {
            rides.onlineAt(city, "MINI", somewhere(city, random));
        }
        List<UUID> riders = new ArrayList<>();
        for (int ride = 0; ride < BOOKERS * RIDES_PER_BOOKER; ride++) {
            riders.add(rides.rider("Rider").id());
        }
        rides.onlyDueIn(city.id());
        SmartLifecycle pollers = context.getBean("pollerRunner", SmartLifecycle.class);

        List<Duration> latencies = Collections.synchronizedList(new ArrayList<>());
        pollers.start();
        try (ExecutorService bookers = Executors.newFixedThreadPool(BOOKERS)) {
            List<Future<?>> running = new ArrayList<>();
            for (int booker = 0; booker < BOOKERS; booker++) {
                List<UUID> own = riders.subList(booker * RIDES_PER_BOOKER, (booker + 1) * RIDES_PER_BOOKER);
                long seed = random.nextLong();
                running.add(bookers.submit(() -> {
                    Random place = new Random(seed);
                    for (UUID rider : own) {
                        latencies.add(bookAndWaitForOffer(city, rider, somewhere(city, place)));
                    }
                    return null;
                }));
            }
            for (Future<?> booker : running) {
                booker.get(2, TimeUnit.MINUTES);
            }
        } finally {
            pollers.stop();
        }

        List<Duration> sorted = latencies.stream().sorted().toList();
        Duration p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
        assertThat(sorted).hasSize(BOOKERS * RIDES_PER_BOOKER);
        assertThat(p95).as("p95 of %s", sorted).isLessThan(LIMIT);
    }

    private Duration bookAndWaitForOffer(TestCity city, UUID riderId, GeoPoint pickup) throws InterruptedException {
        long started = System.nanoTime();
        UUID rideId = rides.book(riderId, city, pickup, "MINI").id();
        while (rides.pendingOffer(rideId) == null) {
            if (System.nanoTime() - started > GIVE_UP.toNanos()) {
                throw new AssertionError("No offer for ride " + rideId + " within " + GIVE_UP);
            }
            Thread.sleep(10);
        }
        return Duration.ofNanos(System.nanoTime() - started);
    }

    /** A point in a 0.1° square inside the city, about 11 km across. */
    private static GeoPoint somewhere(TestCity city, Random random) {
        return city.at(0.05 + random.nextDouble() * 0.1, 0.05 + random.nextDouble() * 0.1);
    }
}
