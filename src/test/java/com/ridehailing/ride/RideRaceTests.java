package com.ridehailing.ride;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.Idempotency;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.Postgis;
import com.ridehailing.support.RaceRunner;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestRides.AssignedRide;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The races on a ride after assignment (ride lifecycle §8, LLD §17.2), each repeated on fresh data in a city of its
 * own: the first transition to commit wins, the other command gets {@code 409} with the state it lost to, and the
 * invariant checks I1–I6 hold after every repetition. Race 4 is in the dispatch races; race 10 with the sweeper's. A
 * window too narrow for timing is forced with a gate, as in phase 7.
 */
@Tag("race")
class RideRaceTests extends IntegrationTest {

    private static final double METRES_PER_DEGREE = 6_371_008.8 * Math.PI / 180;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REFUSED = "409 INVALID_TRANSITION";

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestRides rides;

    @Autowired
    private TestUsers users;

    @Autowired
    private JdbcClient jdbc;

    /**
     * Race 5: the driver starts the trip while they cancel, or mark a no-show, or the rider cancels. All leave
     * {@code DRIVER_ARRIVED}, so the first to commit wins and the other finds the ride moved on.
     */
    @Test
    void endingTheRideWhileTheTripStartsHasOneOutcome() throws Exception {
        Map<String, Integer> seen = new TreeMap<>();
        List<String> others = List.of("driver cancels", "no-show", "rider cancels");
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            AssignedRide ride = assigned();
            assertThat(command(ride.driver().authorization(), ride.id(), "arrive", "{}")).isEqualTo("200");
            jdbc.sql("UPDATE ride.rides SET arrived_at = arrived_at - interval '301 seconds' WHERE id = :id")
                    .param("id", ride.id()).update();
            String other = others.get(repetition % others.size());
            Callable<String> ending = switch (other) {
                case "driver cancels" -> () -> command(ride.driver().authorization(), ride.id(), "cancel", "{}");
                case "no-show" -> () -> command(ride.driver().authorization(), ride.id(), "no-show", "{}");
                default -> () -> command(ride.rider().authorization(), ride.id(), "cancel", "{}");
            };

            List<String> outcomes = RaceRunner.staggered(
                    () -> command(ride.driver().authorization(), ride.id(), "start", pin(ride)), ending);

            String status = rides.rideStatus(ride.id());
            boolean started = outcomes.equals(List.of("200", REFUSED)) && status.equals("IN_TRIP")
                    && availability(ride).equals("ON_TRIP");
            boolean ended = outcomes.equals(List.of(REFUSED, "200"))
                    && status.equals(other.equals("rider cancels") ? "CANCELLED_BY_RIDER" : "CANCELLED_BY_DRIVER")
                    && availability(ride).equals("AVAILABLE");
            assertThat(started || ended).as("repetition %d, %s: %s, ride %s", repetition, other, outcomes, status)
                    .isTrue();
            assertThat(events(ride.id(), "TripStarted", "RideCancelled")).as("repetition %d", repetition)
                    .containsExactly(started ? "TripStarted" : "RideCancelled");
            assertThat(rides.violations(ride.driver().city().id())).as("repetition %d", repetition).isEmpty();
            seen.merge((started ? "start before " : "start after ") + other, 1, Integer::sum);
        }
        RaceRunner.assertExplored(seen, others.stream()
                .flatMap(other -> List.of("start before " + other, "start after " + other).stream())
                .toArray(String[]::new));
    }

    /** Race 8: operations cancel a ride as its driver completes it; versions let one of them through. */
    @Test
    void cancellingAsOperationsDuringCompletionHasOneOutcome() throws Exception {
        Map<String, Integer> seen = new TreeMap<>();
        TestUser ops = users.create(UserRole.OPS);
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            AssignedRide ride = assigned();
            assertThat(command(ride.driver().authorization(), ride.id(), "arrive", "{}")).isEqualTo("200");
            assertThat(command(ride.driver().authorization(), ride.id(), "start", pin(ride))).isEqualTo("200");

            List<String> outcomes = RaceRunner.staggered(
                    () -> command(ride.driver().authorization(), ride.id(), "complete", "{}"),
                    () -> outcome(postJson("/v1/ops/rides/" + ride.id() + "/cancel", headers(ops.authorization()),
                            "{\"reason\": \"race\"}")));

            String status = rides.rideStatus(ride.id());
            boolean completed = outcomes.equals(List.of("200", REFUSED)) && status.equals("COMPLETED");
            boolean cancelled = outcomes.equals(List.of(REFUSED, "200")) && status.equals("CANCELLED_BY_SYSTEM");
            assertThat(completed || cancelled).as("repetition %d: %s, ride %s", repetition, outcomes, status).isTrue();
            assertThat(availability(ride)).as("repetition %d", repetition).isEqualTo("AVAILABLE");
            assertThat(events(ride.id(), "TripCompleted", "RideCancelled")).as("repetition %d", repetition)
                    .containsExactly(completed ? "TripCompleted" : "RideCancelled");
            assertThat(rides.violations(ride.driver().city().id())).as("repetition %d", repetition).isEmpty();
            seen.merge(status, 1, Integer::sum);
        }
        RaceRunner.assertExplored(seen, "COMPLETED", "CANCELLED_BY_SYSTEM");
    }

    /**
     * A window too narrow for timing: the first search's timeout is held by a poller through the acceptance, which
     * can't remove it (§6.2), and fires after the driver cancelled and the ride searches again. It belongs to the
     * earlier search, so the second one goes on.
     */
    @Test
    void theFirstSearchsTimeoutFiringDuringTheSecondDoesNothing() throws Exception {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        GeoPoint pickup = city.at(0.1, 0.1);
        TestDriver driver = rides.onlineAt(city, "MINI", new GeoPoint(pickup.lat() + 100 / METRES_PER_DEGREE,
                pickup.lon()));
        UUID rideId = rides.book(rides.rider("Rider").id(), city, pickup, "MINI").id();
        rides.onlyDueIn(city.id());
        rides.search();
        UUID offer = rides.pendingOffer(rideId);
        UUID firstTimeout = jdbc.sql("""
                        SELECT id FROM platform.timers WHERE kind = 'SEARCH_TIMEOUT' AND aggregate_id = :id
                        """).param("id", rideId).query(UUID.class).single();
        try (Connection gate = Postgis.connection()) {
            gate.setAutoCommit(false);
            try (var lock = gate.prepareStatement("SELECT 1 FROM platform.timers WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, firstTimeout);
                lock.executeQuery().close();
            }
            assertThat(outcome(postJson("/v1/offers/" + offer + "/accept", headers(driver.authorization()), "{}")))
                    .isEqualTo("200");
            assertThat(command(driver.authorization(), rideId, "cancel", "{}")).isEqualTo("200");
            gate.rollback();
        }

        jdbc.sql("UPDATE platform.timers SET due_at = now() + interval '1 hour' WHERE due_at <= now()").update();
        jdbc.sql("UPDATE platform.timers SET due_at = now() - interval '1 second' WHERE id = :id")
                .param("id", firstTimeout).update();
        TestRides.asDispatch(() -> {
            rides.timers().fireAllDue();
            return null;
        });

        assertThat(rides.rideStatus(rideId)).isEqualTo("SEARCHING");
        assertThat(jdbc.sql("""
                        SELECT payload ->> 'generation' FROM platform.timers
                        WHERE kind = 'SEARCH_TIMEOUT' AND aggregate_id = :id
                        """).param("id", rideId).query(String.class).list())
                .as("the first search's timeout fired; the second's waits").containsExactly("2");
        assertThat(events(rideId, "RideNotMatched")).isEmpty();
        assertThat(rides.violations(city.id())).isEmpty();
    }

    /** A ride in a city of its own, accepted by a driver 100 m north of the pickup. */
    private AssignedRide assigned() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        GeoPoint pickup = city.at(0.1, 0.1);
        return rides.assigned(city, pickup, new GeoPoint(pickup.lat() + 100 / METRES_PER_DEGREE, pickup.lon()));
    }

    private String command(String authorization, UUID rideId, String action, String body) {
        return outcome(postJson("/v1/rides/" + rideId + "/" + action, headers(authorization), body));
    }

    private static String pin(AssignedRide ride) {
        return "{\"pin\": \"" + ride.pin() + "\"}";
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

    private String availability(AssignedRide ride) {
        return jdbc.sql("SELECT status FROM dispatch.driver_availability WHERE driver_id = :id")
                .param("id", ride.driver().id()).query(String.class).single();
    }

    /** The ride's events of these types, oldest first. */
    private List<String> events(UUID rideId, String... types) {
        return jdbc.sql("""
                        SELECT event_type FROM platform.outbox
                        WHERE aggregate_id = :id AND event_type = ANY(:types) ORDER BY id
                        """)
                .param("id", rideId)
                .param("types", types)
                .query(String.class)
                .list();
    }
}
