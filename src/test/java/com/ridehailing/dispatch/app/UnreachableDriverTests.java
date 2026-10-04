package com.ridehailing.dispatch.app;

import static com.ridehailing.support.EventContract.outboxEvents;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.dispatch.db.AvailabilityRepository;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.Status;
import com.ridehailing.location.LocationProperties;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.LogContext;
import com.ridehailing.platform.Transactions;
import com.ridehailing.ride.RideAssignment;
import com.ridehailing.ride.RideDispatchParticipant;
import com.ridehailing.ride.RideDispatchParticipant.DriverRelease;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.support.EventContract;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.RaceRunner;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestRides.AssignedRide;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * T7 (LLD §7.9, §8.9): an assigned driver silent for 2 minutes loses the ride, which searches again with priority,
 * unless the safety valve holds. Silence starts no earlier than going online, so the sweeps run at a later instant.
 */
class UnreachableDriverTests extends IntegrationTest {

    private static final double METRES_PER_DEGREE = 6_371_008.8 * Math.PI / 180;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private AvailabilityRepository availability;

    @Autowired
    private Availability service;

    @Autowired
    private RideAssignment assignment;

    @Autowired
    private RideDispatchParticipant participant;

    @Autowired
    private LiveIndex realIndex;

    @Autowired
    private LocationProperties location;

    @Autowired
    private Transactions transactions;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestRides rides;

    @Autowired
    private JdbcClient jdbc;

    private ScriptedLiveIndex index;
    private Sweeper sweeper;
    private TestCity city;
    private Instant later;

    @BeforeEach
    void setUp() {
        index = new ScriptedLiveIndex(realIndex);
        sweeper = new Sweeper(availability, service, assignment, index, location, transactions, Clock.systemUTC(),
                meters);
        city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        later = Instant.now().plus(Duration.ofHours(1));
    }

    @AfterEach
    void invariantsHold() {
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void anAssignedDriverSilentForTwoMinutesLosesTheRide() {
        AssignedRide ride = assigned(0);

        assertThat(sweep(Instant.now().plus(location.unreachableAfter()).plusSeconds(5)))
                .containsExactly(ride.driver().id());

        assertThat(jdbc.sql("""
                        SELECT status || ' ' || search_generation || ' ' || reassign_count FROM ride.rides WHERE id = :id
                        """).param("id", ride.id()).query(String.class).single()).isEqualTo("SEARCHING 2 1");
        assertThat(jdbc.sql("""
                        SELECT command || ' ' || actor_type || ' ' || actor_id FROM ride.transitions
                        WHERE ride_id = :id ORDER BY version DESC LIMIT 1
                        """).param("id", ride.id()).query(String.class).single())
                .isEqualTo("UNREACHABLE SYSTEM sweeper");
        assertThat(outboxEvents(jdbc, "DriverUnassigned", ride.id())).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("reason").asString()).isEqualTo("DRIVER_UNREACHABLE");
        });
        assertThat(availability(ride)).isEqualTo("OFFLINE");
        assertThat(realIndex.mirrored(city.id()).get(ride.driver().id()).status()).isEqualTo(Status.OFFLINE);
        assertThat(jdbc.sql("SELECT offline_reason FROM dispatch.driver_sessions WHERE driver_id = :id")
                .param("id", ride.driver().id()).query(String.class).single()).isEqualTo("UNREACHABLE");
        assertThat(outboxEvents(jdbc, "DriverWentOffline", ride.driver().id())).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("reason").asString()).isEqualTo("UNREACHABLE");
        });
        assertThat(jdbc.sql("SELECT priority FROM dispatch.search_tasks WHERE ride_id = :id")
                .param("id", ride.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT payload ->> 'generation' FROM platform.timers
                        WHERE kind = 'SEARCH_TIMEOUT' AND aggregate_id = :id
                        """).param("id", ride.id()).query(String.class).single()).isEqualTo("2");

        assertProblem("POST", "/v1/rides/{ride_id}/arrive", command(ride, "arrive"), 409, "RIDE_REASSIGNED");
        assertProblem("POST", "/v1/rides/{ride_id}/cancel", command(ride, "cancel"), 409, "RIDE_REASSIGNED");
    }

    @Test
    void twoMinutesOfSilenceIsNotYetUnreachable() {
        AssignedRide exactly = assigned(0);
        AssignedRide longer = assigned(1);
        index.seen.put(exactly.driver().id(), later.minus(location.unreachableAfter()));
        index.seen.put(longer.driver().id(), later.minus(location.unreachableAfter()).minusMillis(1));

        assertThat(sweep(later)).containsExactly(longer.driver().id());
        assertThat(rides.rideStatus(exactly.id())).isEqualTo("DRIVER_ASSIGNED");
    }

    @Test
    void aDriverHeardFromJustBeforeTheTransactionKeepsTheRide() {
        AssignedRide ride = assigned(0);
        index.laterSeen = id -> later.minusSeconds(10);

        assertThat(sweep(later)).isEmpty();
        assertThat(rides.rideStatus(ride.id())).isEqualTo("DRIVER_ASSIGNED");
    }

    @Test
    void aDriverWhoArrivedOrIsOnTheTripKeepsTheRide() {
        AssignedRide arrived = assigned(0);
        AssignedRide onTrip = assigned(1);
        assertThat(command(arrived, "arrive").statusCode()).isEqualTo(200);
        assertThat(command(onTrip, "arrive").statusCode()).isEqualTo(200);
        assertThat(postJson("/v1/rides/" + onTrip.id() + "/start", Map.of("Authorization",
                onTrip.driver().authorization(), Idempotency.HEADER, UUID.randomUUID().toString()),
                "{\"pin\": \"" + onTrip.pin() + "\"}").statusCode()).isEqualTo(200);

        assertThat(sweep(later)).isEmpty();

        assertThat(rides.rideStatus(arrived.id())).isEqualTo("DRIVER_ARRIVED");
        assertThat(rides.rideStatus(onTrip.id())).isEqualTo("IN_TRIP");
        assertThat(availability(arrived)).isEqualTo("ASSIGNED");
        assertThat(TestRides.asDispatch(() -> transactions.execute(
                () -> assignment.unassignUnreachable(arrived.id(), arrived.driver().id()))))
                .as("T7 starts only from DRIVER_ASSIGNED").isFalse();
    }

    /** The valve allows max(5, 10%) of the city's assigned drivers to be silent at once: 6 of 60, not 7. */
    @Test
    void theValveCountsTheShareOfAssignedDrivers() {
        List<AssignedRide> sixty = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            sixty.add(rides.assigned(city, city.at(0.3, 0.3), city.at(0.3, 0.3)));
        }
        sixty.subList(7, 60).forEach(ride -> index.seen.put(ride.driver().id(), later));
        double trips = valveTrips();

        assertThat(sweep(later)).as("7 of 60").isEmpty();
        assertThat(valveTrips()).isEqualTo(trips + 1);

        index.seen.put(sixty.getFirst().driver().id(), later);
        assertThat(sweep(later)).as("6 of 60").hasSize(6);
        assertThat(valveTrips()).isEqualTo(trips + 1);
    }

    @Test
    void sixSilentAssignedDriversAtOnceTripTheValveAndFiveDont() {
        List<AssignedRide> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(assigned(i));
        }
        double trips = valveTrips();

        assertThat(sweep(later)).isEmpty();

        assertThat(valveTrips()).isEqualTo(trips + 1);
        assertThat(six).allSatisfy(ride -> assertThat(rides.rideStatus(ride.id())).isEqualTo("DRIVER_ASSIGNED"));

        index.seen.put(six.getFirst().driver().id(), later);
        assertThat(sweep(later)).hasSize(5);
        assertThat(valveTrips()).isEqualTo(trips + 1);
    }

    @Test
    void aYoungIndexEpochTripsTheValve() {
        AssignedRide ride = assigned(0);
        index.epochs.put(city.id(), later.minusSeconds(60));
        double trips = valveTrips();

        assertThat(sweep(later)).isEmpty();

        assertThat(valveTrips()).isEqualTo(trips + 1);
        assertThat(rides.rideStatus(ride.id())).isEqualTo("DRIVER_ASSIGNED");
    }

    @Test
    void aFailureForOneDriverDoesntStopTheOthers() {
        AssignedRide failing = assigned(0);
        AssignedRide other = assigned(1);
        index.laterSeen = id -> {
            if (id.equals(failing.driver().id())) {
                throw new IllegalStateException("the index didn't answer");
            }
            return later.minus(location.unreachableAfter()).minusSeconds(1);
        };

        assertThat(sweep(later)).containsExactly(other.driver().id());
        assertThat(rides.rideStatus(failing.id())).isEqualTo("DRIVER_ASSIGNED");
    }

    /** The sweeper read the driver as assigned; meanwhile they cancelled and another driver took the ride. */
    @Test
    void aRideTakenByAnotherDriverSinceTheSweepReadItIsLeftAlone() {
        AssignedRide ride = assigned(0);
        assertThat(command(ride, "cancel").statusCode()).isEqualTo(200);
        TestDriver next = rides.onlineAt(city, "MINI", city.at(0.05, 0.1));
        rides.onlyDueIn(city.id());
        rides.search();
        assertThat(postJson("/v1/offers/" + rides.pendingOffer(ride.id()) + "/accept", Map.of("Authorization",
                next.authorization(), Idempotency.HEADER, UUID.randomUUID().toString()), "{}").statusCode())
                .isEqualTo(200);

        assertThat(TestRides.asDispatch(() -> transactions.execute(
                () -> assignment.unassignUnreachable(ride.id(), ride.driver().id())))).isFalse();

        assertThat(jdbc.sql("SELECT status || ' ' || driver_id FROM ride.rides WHERE id = :id")
                .param("id", ride.id()).query(String.class).single()).isEqualTo("DRIVER_ASSIGNED " + next.id());
        assertThat(jdbc.sql("SELECT status FROM dispatch.driver_availability WHERE driver_id = :id")
                .param("id", next.id()).query(String.class).single()).isEqualTo("ASSIGNED");
    }

    /** A release for a ride the driver isn't on breaks I4; it is logged, and the driver is left as they are. */
    @Test
    void releasingADriverFromARideTheyArentOnLeavesThemAlone() {
        AssignedRide ride = assigned(0);

        TestRides.asDispatch(() -> transactions.execute(() -> {
            participant.driverReleased(UUID.randomUUID(), ride.driver().id(), DriverRelease.UNREACHABLE);
            participant.driverReleased(UUID.randomUUID(), ride.driver().id(), DriverRelease.DRIVER_CANCELLED);
            return null;
        }));

        assertThat(availability(ride)).isEqualTo("ASSIGNED");
        assertThat(outboxEvents(jdbc, "DriverWentOffline", ride.driver().id())).isEmpty();
        assertThat(jdbc.sql("SELECT cancelled_after_accept FROM dispatch.driver_stats WHERE driver_id = :id")
                .param("id", ride.driver().id()).query(Integer.class).single()).isZero();
    }

    /**
     * Race 10 (ride lifecycle §8): the driver arrives or cancels, or the rider cancels, as the sweeper unassigns the
     * silent driver. Whatever commits first stands; a driver acting after the sweep gets {@code 409 RIDE_REASSIGNED}.
     * The ride is unassigned at most once, so a ride searching again is on its second search with one timeout.
     */
    @Test
    void actingOnTheRideAsTheSweeperUnassignsItsDriverHasOneOutcome() throws Exception {
        Map<String, Integer> seen = new TreeMap<>();
        List<String> actions = List.of("driver arrives", "driver cancels", "rider cancels");
        for (int repetition = 0; repetition < RaceRunner.repetitions(); repetition++) {
            TestCity raceCity = cities.create("MINI");
            prices.price(raceCity.id(), "MINI");
            GeoPoint pickup = raceCity.at(0.1, 0.1);
            AssignedRide ride = rides.assigned(raceCity, pickup,
                    new GeoPoint(pickup.lat() + 100 / METRES_PER_DEGREE, pickup.lon()));
            String action = actions.get(repetition % actions.size());
            Instant silentLongEnough = Instant.now().plus(location.unreachableAfter()).plusSeconds(5);

            List<String> outcomes = RaceRunner.staggered(() -> outcome(switch (action) {
                case "driver arrives" -> command(ride, "arrive");
                case "driver cancels" -> command(ride, "cancel");
                default -> postJson("/v1/rides/" + ride.id() + "/cancel", Map.of("Authorization",
                        ride.rider().authorization(), Idempotency.HEADER, UUID.randomUUID().toString()), "{}");
            }), () -> sweep(raceCity.id(), silentLongEnough).contains(ride.driver().id()) ? "swept" : "kept");

            String status = rides.rideStatus(ride.id());
            String driver = availability(ride);
            boolean swept = outcomes.getLast().equals("swept");
            String reassigned = "409 RIDE_REASSIGNED SEARCHING OFFLINE";
            String expected = switch (action) {
                case "driver arrives" -> swept ? reassigned : "200 DRIVER_ARRIVED ASSIGNED";
                case "driver cancels" -> swept ? reassigned : "200 SEARCHING AVAILABLE";
                default -> swept ? "200 CANCELLED_BY_RIDER OFFLINE" : "200 CANCELLED_BY_RIDER AVAILABLE";
            };
            assertThat(outcomes.getFirst() + " " + status + " " + driver)
                    .as("repetition %d, %s: %s", repetition, action, outcomes).isEqualTo(expected);
            assertThat(outboxEvents(jdbc, "DriverUnassigned", ride.id())).as("repetition %d", repetition)
                    .hasSize(swept || action.equals("driver cancels") ? 1 : 0);
            if (status.equals("SEARCHING")) {
                assertThat(jdbc.sql("""
                                SELECT r.search_generation || ' ' || string_agg(t.payload ->> 'generation', ',')
                                FROM ride.rides r
                                JOIN platform.timers t ON t.aggregate_id = r.id AND t.kind = 'SEARCH_TIMEOUT'
                                WHERE r.id = :id GROUP BY r.search_generation
                                """).param("id", ride.id()).query(String.class).single())
                        .as("repetition %d: the generation and its one timeout", repetition).isEqualTo("2 2");
            }
            assertThat(rides.violations(raceCity.id())).as("repetition %d", repetition).isEmpty();
            seen.merge(action + (swept ? " after the sweep" : " first"), 1, Integer::sum);
        }
        RaceRunner.assertExplored(seen, actions.stream()
                .flatMap(action -> Stream.of(action + " first", action + " after the sweep"))
                .toArray(String[]::new));
    }

    /** A ride whose driver waits {@code n} × 300 m north of a pickup of its own. */
    private AssignedRide assigned(int n) {
        GeoPoint pickup = city.at(0.05 + n * 0.02, 0.1);
        return rides.assigned(city, pickup, new GeoPoint(pickup.lat() + 100 / METRES_PER_DEGREE, pickup.lon()));
    }

    private HttpResponse<String> command(AssignedRide ride, String action) {
        return postJson("/v1/rides/" + ride.id() + "/" + action, Map.of("Authorization",
                ride.driver().authorization(), Idempotency.HEADER, UUID.randomUUID().toString()), "{}");
    }

    private String availability(AssignedRide ride) {
        return jdbc.sql("SELECT status FROM dispatch.driver_availability WHERE driver_id = :id")
                .param("id", ride.driver().id()).query(String.class).single();
    }

    private List<UUID> sweep(Instant now) {
        return sweep(city.id(), now);
    }

    private List<UUID> sweep(String cityId, Instant now) {
        List<List<UUID>> result = new ArrayList<>(1);
        LogContext.run(Map.of(LogContext.ROLE, "dispatch"), () -> result.add(sweeper.sweep(cityId, now)));
        return result.getFirst();
    }

    /** The status, with the problem code of an error. */
    private static String outcome(HttpResponse<String> response) {
        if (response.statusCode() < 400) {
            return String.valueOf(response.statusCode());
        }
        return response.statusCode() + " " + JSON.readTree(response.body()).path("code").asString();
    }

    private double valveTrips() {
        return meters.counter("sweeper.safety.valve", "rule", "unreachable").count();
    }
}
