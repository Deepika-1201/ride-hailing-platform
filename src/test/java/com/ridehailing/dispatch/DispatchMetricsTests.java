package com.ridehailing.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.Idempotency;
import com.ridehailing.ride.RideView;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestUsers.TestUser;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** LLD §16.1: booking and dispatch metrics, counted once per committed change and exported to Prometheus. */
class DispatchMetricsTests extends IntegrationTest {

    private static final double METRES_PER_DEGREE = 6_371_008.8 * Math.PI / 180;

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestRides rides;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void outcomeCountersStartAtZeroAndEveryMetricIsExported() {
        String scraped = get(managementPort, "/actuator/prometheus").body();

        for (String outcome : List.of("ACCEPTED", "DECLINED", "EXPIRED", "WITHDRAWN")) {
            assertThat(scraped).contains("offers_total{outcome=\"" + outcome + "\"}");
        }
        assertThat(scraped).doesNotContain("offers_total{outcome=\"PENDING\"}");
        for (String outcome : List.of("OFFERED", "NO_CANDIDATES", "ALL_RESERVATIONS_LOST", "INDEX_UNAVAILABLE")) {
            assertThat(scraped).contains("dispatch_search_attempts_total{outcome=\"" + outcome + "\"}");
        }
        assertThat(scraped).contains("dispatch_reservation_conflicts_total");
    }

    @Test
    void aRideIsCountedFromBookingToAssignment() {
        TestCity city = city();
        TestDriver driver = rides.onlineAt(city, "MINI", near(city, 100));
        double offered = attempts("OFFERED");
        double accepted = offers("ACCEPTED");

        RideView ride = rides.book(rides.rider("Rider").id(), city, near(city, 0), "MINI");
        rides.onlyDueIn(city.id());
        rides.search();
        accept(driver, rides.pendingOffer(ride.id()));

        assertThat(counter("ride.requests", "city", city.id(), "category", "MINI")).isEqualTo(1);
        assertThat(attempts("OFFERED") - offered).isEqualTo(1);
        assertThat(timer("dispatch.first.offer", city).count()).isEqualTo(1);
        assertThat(offers("ACCEPTED") - accepted).isEqualTo(1);
        assertThat(timer("ride.assignment", city).count()).isEqualTo(1);
        assertThat(timer("ride.assignment", city).totalTime(TimeUnit.MILLISECONDS)).as("assigned_at - requested_at")
                .isCloseTo(jdbc.sql("""
                        SELECT extract(epoch FROM assigned_at - requested_at) * 1000 FROM ride.rides WHERE id = :id
                        """).param("id", ride.id()).query(Double.class).single(), Offset.offset(1.0));
        String scraped = get(managementPort, "/actuator/prometheus").body();
        assertThat(scraped).contains("ride_requests_total{category=\"MINI\",city=\"" + city.id() + "\"}");
        assertThat(scraped).contains("dispatch_first_offer_seconds_count{city=\"" + city.id() + "\"}");
        assertThat(scraped).contains("ride_assignment_seconds_count{city=\"" + city.id() + "\"}");
        assertThat(scraped).contains("dispatch_first_offer_seconds_bucket{city=\"" + city.id() + "\"");
    }

    @Test
    void onlyTheFirstOfferOfARideIsTimedAndLostReservationsAreConflicts() {
        TestCity city = city();
        TestDriver first = rides.onlineAt(city, "MINI", near(city, 100));
        RideView ride = rides.book(rides.rider("Rider").id(), city, near(city, 0), "MINI");
        rides.onlyDueIn(city.id());
        rides.search();
        double declined = offers("DECLINED");
        assertThat(postJson("/v1/offers/" + rides.pendingOffer(ride.id()) + "/decline", headers(first.authorization()),
                "{}").statusCode()).isEqualTo(200);
        rides.onlineAt(city, "MINI", near(city, 300));
        TestDriver busy = rides.onlineAt(city, "MINI", near(city, 200));
        jdbc.sql("UPDATE dispatch.driver_availability SET status = 'ASSIGNED', ride_id = :ride WHERE driver_id = :id")
                .param("ride", UUID.randomUUID()).param("id", busy.id()).update();
        double conflicts = meters.get("dispatch.reservation.conflicts").counter().count();

        rides.onlyDueIn(city.id());
        rides.search();

        assertThat(rides.pendingOffer(ride.id())).isNotNull();
        assertThat(offers("DECLINED") - declined).isEqualTo(1);
        assertThat(timer("dispatch.first.offer", city).count()).as("the second offer isn't the first").isEqualTo(1);
        assertThat(meters.get("dispatch.reservation.conflicts").counter().count() - conflicts).isEqualTo(1);
    }

    @Test
    void aRideWithoutADriverIsCountedNotMatchedAndItsOfferWithdrawn() {
        TestCity city = city();
        rides.onlineAt(city, "MINI", near(city, 100));
        RideView ride = rides.book(rides.rider("Rider").id(), city, near(city, 0), "MINI");
        rides.onlyDueIn(city.id());
        rides.search();
        double withdrawn = offers("WITHDRAWN");

        rides.fire("SEARCH_TIMEOUT", ride.id());

        assertThat(counter("rides.not.matched", "city", city.id())).isEqualTo(1);
        assertThat(offers("WITHDRAWN") - withdrawn).isEqualTo(1);
    }

    @Test
    void aRefusedBookingIsNotCounted() {
        TestCity city = city();
        TestUser rider = rides.rider("Rider");
        rides.book(rider.id(), city, near(city, 0), "MINI");
        UUID quote = rides.quote(rider.id(), near(city, 0), near(city, 5_000), "MINI");

        assertThat(postJson("/v1/rides", headers(rider.authorization()), "{\"quote_id\": \"" + quote + "\"}")
                .statusCode()).isEqualTo(409);

        assertThat(counter("ride.requests", "city", city.id(), "category", "MINI")).isEqualTo(1);
    }

    private void accept(TestDriver driver, UUID offerId) {
        assertThat(postJson("/v1/offers/" + offerId + "/accept", headers(driver.authorization()), "{}").statusCode())
                .isEqualTo(200);
    }

    private static Map<String, String> headers(String authorization) {
        return Map.of("Authorization", authorization, Idempotency.HEADER, UUID.randomUUID().toString());
    }

    private double counter(String name, String... tags) {
        Counter counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private double attempts(String outcome) {
        return counter("dispatch.search.attempts", "outcome", outcome);
    }

    private double offers(String outcome) {
        return counter("offers", "outcome", outcome);
    }

    private Timer timer(String name, TestCity city) {
        return meters.get(name).tag("city", city.id()).timer();
    }

    private TestCity city() {
        TestCity created = cities.create("MINI");
        prices.price(created.id(), "MINI");
        return created;
    }

    private static GeoPoint near(TestCity city, double metresNorth) {
        GeoPoint pickup = city.at(0.1, 0.1);
        return new GeoPoint(pickup.lat() + metresNorth / METRES_PER_DEGREE, pickup.lon());
    }
}
