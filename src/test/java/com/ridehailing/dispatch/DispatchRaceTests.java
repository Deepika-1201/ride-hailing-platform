package com.ridehailing.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.Poller;
import com.ridehailing.ride.RideView;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.support.Eventually;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.Postgis;
import com.ridehailing.support.RaceRunner;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestUsers.TestUser;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The races of dispatch §6 and ride lifecycle §8 that phase 7 can run (LLD §17.2), each repeated on fresh data in a
 * city of its own, followed by the invariant checks I1–I3.
 */
class DispatchRaceTests extends IntegrationTest {

    private static final double METRES_PER_DEGREE = 6_371_008.8 * Math.PI / 180;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestRides rides;

    @Autowired
    private JdbcClient jdbc;

    /** Race 1: 2–10 searches reserve the same driver; one offer, the others move on. */
    @Test
    void searchesRacingForOneDriverMakeOneOffer() throws Exception {
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            TestCity city = city();
            TestDriver driver = rides.onlineAt(city, "MINI", near(city, 100));
            int searches = 2 + repetition % 9;
            for (int ride = 0; ride < searches; ride++) {
                rides.book(rides.rider("Rider").id(), city, pickup(city), "MINI");
            }
            rides.onlyDueIn(city.id());
            Poller poller = rides.searchTaskPoller();
            List<Callable<String>> attempts = new ArrayList<>();
            for (int search = 0; search < searches; search++) {
                attempts.add(() -> String.valueOf(TestRides.asDispatch(poller::poll)));
            }

            RaceRunner.race(attempts);

            assertThat(count("""
                    SELECT count(*) FROM dispatch.offers WHERE driver_id = :driver AND status = 'PENDING'
                    """, Map.of("driver", driver.id()))).as("repetition %d", repetition).isEqualTo(1);
            assertThat(rides.violations(city.id())).as("repetition %d", repetition).isEmpty();
        }
    }

    /** Race 2: dispatch nodes on the same ride; one claims the task, one offer. */
    @Test
    void pollersRacingForOneRideMakeOneOffer() throws Exception {
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            TestCity city = city();
            for (int driver = 1; driver <= 3; driver++) {
                rides.onlineAt(city, "MINI", near(city, 100 * driver));
            }
            RideView ride = rides.book(rides.rider("Rider").id(), city, pickup(city), "MINI");
            rides.onlyDueIn(city.id());
            Poller poller = rides.searchTaskPoller();
            int nodes = 2 + repetition % 9;
            List<Callable<String>> attempts = new ArrayList<>();
            for (int node = 0; node < nodes; node++) {
                attempts.add(() -> String.valueOf(TestRides.asDispatch(poller::poll)));
            }

            List<String> outcomes = RaceRunner.race(attempts);

            assertThat(outcomes).as("repetition %d", repetition).containsOnlyOnce("true");
            assertThat(count("SELECT count(*) FROM dispatch.offers WHERE ride_id = :ride",
                    Map.of("ride", ride.id()))).as("repetition %d", repetition).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM dispatch.decisions WHERE ride_id = :ride",
                    Map.of("ride", ride.id()))).as("repetition %d", repetition).isEqualTo(1);
            assertThat(rides.violations(city.id())).as("repetition %d", repetition).isEmpty();
        }
    }

    /** Race 3: a driver accepts two requests at once; only their live offer can win, the old one gets 409. */
    @Test
    void aDriverAcceptingTwoOffersGetsOneRide() throws Exception {
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            TestCity city = city();
            TestDriver driver = rides.onlineAt(city, "MINI", near(city, 100));
            RideView first = rides.book(rides.rider("Rider").id(), city, pickup(city), "MINI");
            UUID old = offer(city, first);
            rides.fire("OFFER_EXPIRY", old);
            RideView second = rides.book(rides.rider("Rider").id(), city, pickup(city), "MINI");
            UUID live = offer(city, second);

            List<String> outcomes = RaceRunner.race(() -> accept(driver, old), () -> accept(driver, live));

            assertThat(outcomes).as("repetition %d", repetition)
                    .containsExactly("409 OFFER_NO_LONGER_AVAILABLE", "200");
            assertThat(rides.violations(city.id())).as("repetition %d", repetition).isEmpty();
        }
    }

    /** Accept at the moment the offer expires: exactly one wins. */
    @Test
    void acceptingAsTheOfferExpiresHasOneOutcome() throws Exception {
        Map<String, Integer> seen = new TreeMap<>();
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            TestCity city = city();
            TestDriver driver = rides.onlineAt(city, "MINI", near(city, 100));
            RideView ride = rides.book(rides.rider("Rider").id(), city, pickup(city), "MINI");
            UUID offer = offer(city, ride);
            rides.timers().makeDue("OFFER_EXPIRY", offer);

            List<String> outcomes = RaceRunner.staggered(() -> accept(driver, offer), () -> fireTimers());

            String accepted = outcomes.getFirst();
            String status = rides.offerStatus(offer);
            assertThat(accepted.equals("200") ? status.equals("ACCEPTED") && rides.rideStatus(ride.id())
                    .equals("DRIVER_ASSIGNED") : accepted.equals("409 OFFER_NO_LONGER_AVAILABLE")
                    && status.equals("EXPIRED") && rides.rideStatus(ride.id()).equals("SEARCHING"))
                    .as("repetition %d: %s, offer %s", repetition, outcomes, status).isTrue();
            assertThat(rides.timers().exists("OFFER_EXPIRY", offer)).as("repetition %d", repetition).isFalse();
            assertThat(rides.violations(city.id())).as("repetition %d", repetition).isEmpty();
            seen.merge(status, 1, Integer::sum);
        }
        RaceRunner.assertExplored(seen, "ACCEPTED", "EXPIRED");
    }

    /** Accept at the moment the search times out: exactly one wins. */
    @Test
    void acceptingAsTheSearchTimesOutHasOneOutcome() throws Exception {
        Map<String, Integer> seen = new TreeMap<>();
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            TestCity city = city();
            TestDriver driver = rides.onlineAt(city, "MINI", near(city, 100));
            RideView ride = rides.book(rides.rider("Rider").id(), city, pickup(city), "MINI");
            UUID offer = offer(city, ride);
            rides.timers().makeDue("SEARCH_TIMEOUT", ride.id());

            List<String> outcomes = RaceRunner.staggered(() -> accept(driver, offer), () -> fireTimers());

            String accepted = outcomes.getFirst();
            String rideStatus = rides.rideStatus(ride.id());
            assertThat(accepted.equals("200") ? rideStatus.equals("DRIVER_ASSIGNED")
                    && rides.offerStatus(offer).equals("ACCEPTED") : accepted.equals("409 OFFER_NO_LONGER_AVAILABLE")
                    && rideStatus.equals("DRIVER_NOT_FOUND") && rides.offerStatus(offer).equals("WITHDRAWN"))
                    .as("repetition %d: %s, ride %s", repetition, outcomes, rideStatus).isTrue();
            assertThat(rides.timers().exists("SEARCH_TIMEOUT", ride.id())).as("repetition %d", repetition).isFalse();
            assertThat(rides.timers().exists("OFFER_EXPIRY", offer)).as("repetition %d", repetition).isFalse();
            assertThat(rides.violations(city.id())).as("repetition %d", repetition).isEmpty();
            seen.merge(rideStatus, 1, Integer::sum);
        }
        RaceRunner.assertExplored(seen, "DRIVER_ASSIGNED", "DRIVER_NOT_FOUND");
    }

    /**
     * The expiry timer is being fired while the driver accepts: acceptance can't remove a timer a poller holds
     * (§6.2), so the timer fires after the acceptance commits and must find the offer accepted and do nothing.
     */
    @Test
    void anExpiryHeldByAPollerFiresAfterAcceptanceAndDoesNothing() throws Exception {
        TestCity city = city();
        TestDriver driver = rides.onlineAt(city, "MINI", near(city, 100));
        RideView ride = rides.book(rides.rider("Rider").id(), city, pickup(city), "MINI");
        UUID offer = offer(city, ride);

        acceptWhileHolding("OFFER_EXPIRY", offer, driver, offer);
        fireTimers();

        assertThat(rides.offerStatus(offer)).isEqualTo("ACCEPTED");
        assertThat(rides.rideStatus(ride.id())).isEqualTo("DRIVER_ASSIGNED");
        assertThat(availability(driver)).isEqualTo("ASSIGNED");
        assertThat(rides.timers().exists("OFFER_EXPIRY", offer)).as("fired, not failed").isFalse();
        assertThat(rides.violations(city.id())).isEmpty();
    }

    /** As above for the search timeout: it fires after the acceptance and must leave the assigned ride alone. */
    @Test
    void aSearchTimeoutHeldByAPollerFiresAfterAcceptanceAndDoesNothing() throws Exception {
        TestCity city = city();
        TestDriver driver = rides.onlineAt(city, "MINI", near(city, 100));
        RideView ride = rides.book(rides.rider("Rider").id(), city, pickup(city), "MINI");
        UUID offer = offer(city, ride);

        acceptWhileHolding("SEARCH_TIMEOUT", ride.id(), driver, offer);
        fireTimers();

        assertThat(rides.rideStatus(ride.id())).isEqualTo("DRIVER_ASSIGNED");
        assertThat(rides.offerStatus(offer)).isEqualTo("ACCEPTED");
        assertThat(rides.timers().exists("SEARCH_TIMEOUT", ride.id())).as("fired, not failed").isFalse();
        assertThat(rides.violations(city.id())).isEmpty();
    }

    /**
     * An attempt holds the ride with {@code FOR SHARE} until its offer commits, so a cancel arriving mid-attempt waits
     * and then withdraws the new offer. The gate holds the driver's row to stop the attempt mid-way.
     */
    @Test
    void aCancelDuringAnAttemptWaitsForItsOfferAndWithdrawsIt() throws Exception {
        TestCity city = city();
        TestDriver driver = rides.onlineAt(city, "MINI", near(city, 100));
        TestUser rider = rides.rider("Rider");
        RideView ride = rides.book(rider.id(), city, pickup(city), "MINI");
        rides.onlyDueIn(city.id());
        Poller poller = rides.searchTaskPoller();
        try (Connection gate = holding("dispatch.driver_availability", "driver_id", driver.id());
                ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<Boolean> attempt = threads.submit(() -> TestRides.asDispatch(poller::poll));
            Eventually.within(Duration.ofSeconds(10), () -> assertThat(sessionsWaitingForALock()).isEqualTo(1));
            Future<String> cancelled = threads.submit(() -> cancel(rider, ride.id()));
            Eventually.within(Duration.ofSeconds(10), () -> assertThat(sessionsWaitingForALock()).isEqualTo(2));
            gate.rollback();

            assertThat(attempt.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(cancelled.get(30, TimeUnit.SECONDS)).isEqualTo("200");
        }

        assertThat(jdbc.sql("SELECT status || ' ' || end_reason FROM dispatch.offers WHERE ride_id = :ride")
                .param("ride", ride.id()).query(String.class).single()).isEqualTo("WITHDRAWN RIDER_CANCELLED");
        assertThat(availability(driver)).isEqualTo("AVAILABLE");
        assertThat(rides.violations(city.id())).isEmpty();
    }

    /** The rider cancels during a search attempt: no offer left pending, the driver free again. */
    @Test
    void cancellingDuringAnAttemptLeavesNoOffer() throws Exception {
        Map<String, Integer> seen = new TreeMap<>();
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            TestCity city = city();
            TestDriver driver = rides.onlineAt(city, "MINI", near(city, 100));
            TestUser rider = rides.rider("Rider");
            RideView ride = rides.book(rider.id(), city, pickup(city), "MINI");
            rides.onlyDueIn(city.id());
            Poller poller = rides.searchTaskPoller();

            List<String> outcomes = RaceRunner.staggered(() -> cancel(rider, ride.id()),
                    () -> String.valueOf(TestRides.asDispatch(poller::poll)));

            assertThat(outcomes.getFirst()).as("repetition %d", repetition).isEqualTo("200");
            assertThat(rides.pendingOffer(ride.id())).as("repetition %d", repetition).isNull();
            assertThat(availability(driver)).as("repetition %d", repetition).isEqualTo("AVAILABLE");
            assertThat(rides.violations(city.id())).as("repetition %d", repetition).isEmpty();
            long offers = count("SELECT count(*) FROM dispatch.offers WHERE ride_id = :ride", Map.of("ride", ride.id()));
            seen.merge(offers == 0 ? "cancelled first" : "offered first", 1, Integer::sum);
        }
        RaceRunner.assertExplored(seen, "cancelled first", "offered first");
    }

    /**
     * Race 4 (ride lifecycle §8): the rider cancels while the driver accepts. Cancelling first withdraws the offer;
     * accepting first makes the cancel a T8, free within the first 2 minutes. Either way the driver is free again.
     */
    @Test
    void cancellingDuringAcceptanceHasOneOutcome() throws Exception {
        Map<String, Integer> seen = new TreeMap<>();
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            TestCity city = city();
            TestDriver driver = rides.onlineAt(city, "MINI", near(city, 100));
            TestUser rider = rides.rider("Rider");
            RideView ride = rides.book(rider.id(), city, pickup(city), "MINI");
            UUID offer = offer(city, ride);

            List<String> outcomes = RaceRunner.staggered(() -> accept(driver, offer), () -> cancel(rider, ride.id()));

            boolean acceptedFirst = outcomes.equals(List.of("200", "200"));
            boolean cancelledFirst = outcomes.equals(List.of("409 OFFER_NO_LONGER_AVAILABLE", "200"));
            assertThat(acceptedFirst || cancelledFirst).as("repetition %d: %s", repetition, outcomes).isTrue();
            assertThat(rides.rideStatus(ride.id())).as("repetition %d", repetition).isEqualTo("CANCELLED_BY_RIDER");
            assertThat(rides.offerStatus(offer)).as("repetition %d", repetition)
                    .isEqualTo(acceptedFirst ? "ACCEPTED" : "WITHDRAWN");
            assertThat(availability(driver)).as("repetition %d", repetition).isEqualTo("AVAILABLE");
            assertThat(jdbc.sql("SELECT fee_paise IS NULL FROM ride.rides WHERE id = :id").param("id", ride.id())
                    .query(Boolean.class).single()).as("repetition %d: free within the window", repetition).isTrue();
            assertThat(rides.violations(city.id())).as("repetition %d", repetition).isEmpty();
            seen.merge(acceptedFirst ? "accepted first" : "cancelled first", 1, Integer::sum);
        }
        RaceRunner.assertExplored(seen, "accepted first", "cancelled first");
    }

    /** Go offline while an offer arrives: no offer is left on an offline driver. */
    @Test
    void goingOfflineAsAnOfferArrivesLeavesNoOfferOnAnOfflineDriver() throws Exception {
        Map<String, Integer> seen = new TreeMap<>();
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            TestCity city = city();
            TestDriver driver = rides.onlineAt(city, "MINI", near(city, 100));
            RideView ride = rides.book(rides.rider("Rider").id(), city, pickup(city), "MINI");
            rides.onlyDueIn(city.id());
            Poller poller = rides.searchTaskPoller();

            List<String> outcomes = RaceRunner.staggered(() -> goOffline(driver),
                    () -> String.valueOf(TestRides.asDispatch(poller::poll)));

            String status = availability(driver);
            boolean offline = outcomes.getFirst().equals("200") && status.equals("OFFLINE");
            boolean triesAgain = outcomes.getFirst().equals("409 INVALID_TRANSITION") && status.equals("OFFERED");
            assertThat(offline || triesAgain).as("repetition %d: %s, driver %s", repetition, outcomes, status)
                    .isTrue();
            assertThat(rides.violations(city.id())).as("repetition %d", repetition).isEmpty();
            UUID offer = jdbc.sql("SELECT id FROM dispatch.offers WHERE ride_id = :ride").param("ride", ride.id())
                    .query(UUID.class).optional().orElse(null);
            seen.merge(offer == null ? "offline first" : "offer " + rides.offerStatus(offer), 1, Integer::sum);
        }
        RaceRunner.assertExplored(seen, "offline first", "offer DECLINED");
    }

    /**
     * The offer lands between going offline's lockless read and its lock: the gate holds the driver's row while the
     * attempt, then going offline, queue for it. The driver is told to try again, and the retry declines the offer.
     */
    @Test
    void anOfferLandingAsTheDriverGoesOfflineMakesThemTryAgain() throws Exception {
        TestCity city = city();
        TestDriver driver = rides.onlineAt(city, "MINI", near(city, 100));
        RideView ride = rides.book(rides.rider("Rider").id(), city, pickup(city), "MINI");
        rides.onlyDueIn(city.id());
        Poller poller = rides.searchTaskPoller();
        try (Connection gate = holding("dispatch.driver_availability", "driver_id", driver.id());
                ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<Boolean> attempt = threads.submit(() -> TestRides.asDispatch(poller::poll));
            Eventually.within(Duration.ofSeconds(10), () -> assertThat(sessionsWaitingForALock()).isEqualTo(1));
            Future<String> offline = threads.submit(() -> goOffline(driver));
            Eventually.within(Duration.ofSeconds(10), () -> assertThat(sessionsWaitingForALock()).isEqualTo(2));
            gate.rollback();

            assertThat(attempt.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(offline.get(30, TimeUnit.SECONDS)).isEqualTo("409 INVALID_TRANSITION");
        }
        UUID offer = rides.pendingOffer(ride.id());
        assertThat(offer).isNotNull();
        assertThat(rides.violations(city.id())).isEmpty();

        assertThat(goOffline(driver)).isEqualTo("200");

        assertThat(rides.offerStatus(offer)).isEqualTo("DECLINED");
        assertThat(availability(driver)).isEqualTo("OFFLINE");
        assertThat(rides.violations(city.id())).isEmpty();
    }

    /** Two bookings by one rider, and one booking sent twice with its key: one ride either way. */
    @Test
    void bookingTwiceAtOnceMakesOneRide() throws Exception {
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            TestCity city = city();
            TestUser rider = rides.rider("Rider");
            GeoPoint pickup = pickup(city);
            GeoPoint dropoff = near(city, 5_000);
            UUID first = rides.quote(rider.id(), pickup, dropoff, "MINI");
            UUID second = rides.quote(rider.id(), pickup, dropoff, "MINI");

            List<String> outcomes = RaceRunner.race(() -> book(rider, first, UUID.randomUUID().toString()),
                    () -> book(rider, second, UUID.randomUUID().toString()));

            assertThat(outcomes).as("repetition %d", repetition)
                    .containsExactlyInAnyOrder("201", "409 ACTIVE_RIDE_EXISTS");
            assertThat(activeRides(rider)).as("repetition %d", repetition).isEqualTo(1);

            TestUser again = rides.rider("Rider");
            UUID quote = rides.quote(again.id(), pickup, dropoff, "MINI");
            String key = UUID.randomUUID().toString();
            List<String> sameKey = RaceRunner.race(() -> book(again, quote, key), () -> book(again, quote, key));

            assertThat(sameKey).as("repetition %d", repetition).contains("201")
                    .allMatch(outcome -> outcome.equals("201") || outcome.equals("409 IDEMPOTENCY_KEY_IN_PROGRESS"));
            assertThat(activeRides(again)).as("repetition %d", repetition).isEqualTo(1);
            assertThat(rides.violations(city.id())).as("repetition %d", repetition).isEmpty();
        }
    }

    private TestCity city() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        return city;
    }

    private UUID offer(TestCity city, RideView ride) {
        rides.onlyDueIn(city.id());
        rides.search();
        UUID offer = rides.pendingOffer(ride.id());
        assertThat(offer).as("an offer for ride %s", ride.id()).isNotNull();
        return offer;
    }

    private String fireTimers() {
        TestRides.asDispatch(() -> {
            rides.timers().fireAllDue();
            return null;
        });
        return "fired";
    }

    private String accept(TestDriver driver, UUID offerId) {
        return outcome(postJson("/v1/offers/" + offerId + "/accept", headers(driver.authorization()), "{}"));
    }

    private String cancel(TestUser rider, UUID rideId) {
        return outcome(postJson("/v1/rides/" + rideId + "/cancel", headers(rider.authorization()), "{}"));
    }

    private String goOffline(TestDriver driver) {
        return outcome(postJson("/v1/drivers/me/offline", headers(driver.authorization()), "{}"));
    }

    private long sessionsWaitingForALock() {
        return jdbc.sql("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE wait_event_type = 'Lock' AND datname = current_database()
                        """)
                .query(Long.class)
                .single();
    }

    /** A session of its own holding one row, as another transaction would; roll it back to let go. */
    private static Connection holding(String table, String column, UUID id) throws SQLException {
        Connection gate = Postgis.connection();
        gate.setAutoCommit(false);
        try (var lock = gate.prepareStatement("SELECT 1 FROM " + table + " WHERE " + column + " = ? FOR UPDATE")) {
            lock.setObject(1, id);
            lock.executeQuery().close();
        }
        return gate;
    }

    /**
     * Accepts while a poller holds the aggregate's timer of the kind, so acceptance can't remove it, then lets go;
     * the timer is still there, for the caller to fire.
     */
    private void acceptWhileHolding(String kind, UUID aggregateId, TestDriver driver, UUID offer) throws SQLException {
        try (Connection gate = Postgis.connection()) {
            gate.setAutoCommit(false);
            try (var lock = gate.prepareStatement(
                    "SELECT 1 FROM platform.timers WHERE kind = ? AND aggregate_id = ? FOR UPDATE")) {
                lock.setString(1, kind);
                lock.setObject(2, aggregateId);
                assertThat(lock.executeQuery().next()).as("the %s timer exists", kind).isTrue();
            }
            assertThat(accept(driver, offer)).isEqualTo("200");
            gate.rollback();
        }
        assertThat(rides.timers().exists(kind, aggregateId)).as("skipped while held").isTrue();
        rides.timers().makeDue(kind, aggregateId);
    }

    private String book(TestUser rider, UUID quoteId, String key) {
        return outcome(postJson("/v1/rides", Map.of("Authorization", rider.authorization(), Idempotency.HEADER, key),
                "{\"quote_id\": \"" + quoteId + "\"}"));
    }

    private static Map<String, String> headers(String authorization) {
        return Map.of("Authorization", authorization, Idempotency.HEADER, UUID.randomUUID().toString());
    }

    /** The status, with the problem code of an error. */
    private static String outcome(HttpResponse<String> response) {
        if (response.statusCode() < 400) {
            return String.valueOf(response.statusCode());
        }
        return response.statusCode() + " " + JSON.readTree(response.body()).path("code").asString();
    }

    private String availability(TestDriver driver) {
        return jdbc.sql("SELECT status FROM dispatch.driver_availability WHERE driver_id = :id")
                .param("id", driver.id()).query(String.class).single();
    }

    private long activeRides(TestUser rider) {
        return count("""
                SELECT count(*) FROM ride.rides
                WHERE rider_id = :rider AND status IN ('SEARCHING', 'DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_TRIP')
                """, Map.of("rider", rider.id()));
    }

    private long count(String sql, Map<String, ?> params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private static GeoPoint pickup(TestCity city) {
        return city.at(0.1, 0.1);
    }

    private static GeoPoint near(TestCity city, double metresNorth) {
        GeoPoint pickup = pickup(city);
        return new GeoPoint(pickup.lat() + metresNorth / METRES_PER_DEGREE, pickup.lon());
    }
}
