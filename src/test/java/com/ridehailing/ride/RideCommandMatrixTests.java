package com.ridehailing.ride;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.Transactions;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestRides.AssignedRide;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Every client command in every state (ride lifecycle §3, §4; LLD §7.1). A command the transition table allows makes
 * its transition. Any other is recognized as already done ({@code 200}), or refused: {@code 409} with the current
 * status and version, {@code 404} to a driver who never had the ride, {@code 409 RIDE_REASSIGNED} to one taken off
 * it. Refusals and repeats change nothing. T1–T3 and T7 have no command on a ride: booking, offer and sweeper tests
 * cover them.
 */
class RideCommandMatrixTests extends IntegrationTest {

    private static final double METRES_PER_DEGREE = 6_371_008.8 * Math.PI / 180;
    private static final String NF = "404 NOT_FOUND";
    private static final String IT = "409 INVALID_TRANSITION";
    private static final String RR = "409 RIDE_REASSIGNED";
    private static final String EARLY = "409 NO_SHOW_TOO_EARLY";
    private static final String DONE = "200";
    private static final Map<String, RideStatus> TRANSITIONS = Map.of("T4", RideStatus.CANCELLED_BY_RIDER,
            "T5", RideStatus.DRIVER_ARRIVED, "T6", RideStatus.SEARCHING, "T8", RideStatus.CANCELLED_BY_RIDER,
            "T9", RideStatus.IN_TRIP, "T11", RideStatus.CANCELLED_BY_DRIVER, "T12", RideStatus.COMPLETED,
            "T13", RideStatus.CANCELLED_BY_SYSTEM);

    /** The table's columns, with the documented path and the transition log's command and actor. */
    enum Command {
        ARRIVE("/v1/rides/{ride_id}/arrive", "ARRIVE DRIVER"),
        START("/v1/rides/{ride_id}/start", "START DRIVER"),
        NO_SHOW("/v1/rides/{ride_id}/no-show", "NO_SHOW DRIVER"),
        COMPLETE("/v1/rides/{ride_id}/complete", "COMPLETE DRIVER"),
        RIDER_CANCEL("/v1/rides/{ride_id}/cancel", "CANCEL RIDER"),
        DRIVER_CANCEL("/v1/rides/{ride_id}/cancel", "CANCEL DRIVER"),
        OPS_CANCEL("/v1/ops/rides/{ride_id}/cancel", "CANCEL OPS");

        final String path;
        final String logged;

        Command(String path, String logged) {
            this.path = path;
            this.logged = logged;
        }
    }

    /** A state, reached the way named, since that decides some answers; then each command's answer. */
    enum Setup {
        //                              ARRIVE START  NO_SHOW COMPLETE RIDER_CANCEL DRIVER_CANCEL OPS_CANCEL
        SEARCHING(                      NF,    NF,    NF,     NF,      "T4",        NF,           "T13"),
        SEARCHING_AFTER_DRIVER_CANCEL(  RR,    RR,    RR,     RR,      "T4",        DONE,         "T13"),
        SEARCHING_AFTER_UNREACHABLE(    RR,    RR,    RR,     RR,      "T4",        RR,           "T13"),
        ASSIGNED(                       "T5",  IT,    IT,     IT,      "T8",        "T6",         "T13"),
        ARRIVED(                        DONE,  "T9",  EARLY,  IT,      "T8",        "T11",        "T13"),
        IN_TRIP(                        IT,    DONE,  IT,     "T12",   IT,          IT,           "T13"),
        COMPLETED(                      IT,    DONE,  IT,     DONE,    IT,          IT,           IT),
        CANCELLED_WHILE_SEARCHING(      NF,    NF,    NF,     NF,      DONE,        NF,           IT),
        CANCELLED_BY_RIDER(             IT,    IT,    IT,     IT,      DONE,        IT,           IT),
        CANCELLED_AT_PICKUP(            IT,    IT,    IT,     IT,      IT,          DONE,         IT),
        NO_SHOW(                        IT,    IT,    DONE,   IT,      IT,          IT,           IT),
        CANCELLED_BY_SYSTEM(            IT,    IT,    IT,     IT,      IT,          IT,           IT),
        NOT_MATCHED(                    NF,    NF,    NF,     NF,      IT,          NF,           IT);

        private final List<String> answers;

        Setup(String... answers) {
            this.answers = List.of(answers);
        }

        String answer(Command command) {
            return answers.get(command.ordinal());
        }
    }

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestRides rides;

    @Autowired
    private TestDrivers drivers;

    @Autowired
    private TestUsers users;

    @Autowired
    private RideAssignment assignment;

    @Autowired
    private Transactions transactions;

    @Autowired
    private JdbcClient jdbc;

    @ParameterizedTest
    @EnumSource(Setup.class)
    void aCommandTheStateDoesntAllowIsRefusedOrRecognizedAndChangesNothing(Setup setup) {
        Ride ride = make(setup);
        String before = state(ride);
        int version = version(ride);

        for (Command command : Command.values()) {
            String expected = setup.answer(command);
            if (expected.startsWith("T")) {
                continue;
            }
            HttpResponse<String> response = send(command, ride);
            JsonNode body = assertAnswered("POST", command.path, response, Integer.parseInt(expected.substring(0, 3)));
            String where = setup + " " + command;
            assertThat(outcome(response, body)).as(where).isEqualTo(expected);
            if (expected.equals(DONE)) {
                assertThat(body.get("version").asInt()).as(where).isEqualTo(version);
            } else if (expected.equals(IT)) {
                assertThat(body.get("current_status").asString()).as(where).isEqualTo(status(ride).name());
                assertThat(body.get("current_version").asInt()).as(where).isEqualTo(version);
            }
            assertThat(state(ride)).as("%s changed nothing", where).isEqualTo(before);
        }
        assertThat(rides.violations(ride.city().id())).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("allowed")
    void aCommandTheStateAllowsMakesItsTransition(Setup setup, Command command) {
        Ride ride = make(setup);
        int version = version(ride);
        RideStatus to = TRANSITIONS.get(setup.answer(command));

        JsonNode answered = assertAnswered("POST", command.path, send(command, ride), 200);

        assertThat(answered.get("status").asString()).isEqualTo(to.name());
        assertThat(answered.get("version").asInt()).isEqualTo(version + 1);
        assertThat(status(ride)).isEqualTo(to);
        assertThat(jdbc.sql("""
                        SELECT to_status || ' ' || command || ' ' || actor_type FROM ride.transitions
                        WHERE ride_id = :id ORDER BY version DESC LIMIT 1
                        """).param("id", ride.id()).query(String.class).single()).isEqualTo(to + " " + command.logged);
        assertThat(rides.violations(ride.city().id())).isEmpty();
    }

    static Stream<Arguments> allowed() {
        return Arrays.stream(Setup.values()).flatMap(setup -> Arrays.stream(Command.values())
                .filter(command -> setup.answer(command).startsWith("T"))
                .map(command -> Arguments.of(setup, command)));
    }

    /** A ride in a city of its own, in the setup's state; its driver is a stranger when it never had one. */
    private Ride make(Setup setup) {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        GeoPoint pickup = city.at(0.1, 0.1);
        TestUser ops = users.create(UserRole.OPS);
        if (setup == Setup.SEARCHING || setup == Setup.CANCELLED_WHILE_SEARCHING || setup == Setup.NOT_MATCHED) {
            TestUser rider = rides.rider("Rider");
            Ride ride = new Ride(rides.book(rider.id(), city, pickup, "MINI").id(), city, rider,
                    drivers.create(city, "MINI"), ops, "0000");
            if (setup == Setup.CANCELLED_WHILE_SEARCHING) {
                done(Command.RIDER_CANCEL, ride);
            } else if (setup == Setup.NOT_MATCHED) {
                rides.fire("SEARCH_TIMEOUT", ride.id());
            }
            return ride;
        }
        AssignedRide assigned = rides.assigned(city, pickup,
                new GeoPoint(pickup.lat() + 100 / METRES_PER_DEGREE, pickup.lon()));
        Ride ride = new Ride(assigned.id(), city, assigned.rider(), assigned.driver(), ops, assigned.pin());
        switch (setup) {
            case SEARCHING_AFTER_DRIVER_CANCEL -> done(Command.DRIVER_CANCEL, ride);
            case SEARCHING_AFTER_UNREACHABLE -> assertThat(TestRides.asDispatch(() -> transactions.execute(
                    () -> assignment.unassignUnreachable(ride.id(), ride.driver().id())))).isTrue();
            case ARRIVED -> done(Command.ARRIVE, ride);
            case IN_TRIP -> done(ride, Command.ARRIVE, Command.START);
            case COMPLETED -> done(ride, Command.ARRIVE, Command.START, Command.COMPLETE);
            case CANCELLED_BY_RIDER -> done(Command.RIDER_CANCEL, ride);
            case CANCELLED_AT_PICKUP -> done(ride, Command.ARRIVE, Command.DRIVER_CANCEL);
            case NO_SHOW -> {
                done(Command.ARRIVE, ride);
                jdbc.sql("UPDATE ride.rides SET arrived_at = arrived_at - interval '301 seconds' WHERE id = :id")
                        .param("id", ride.id()).update();
                done(Command.NO_SHOW, ride);
            }
            case CANCELLED_BY_SYSTEM -> done(Command.OPS_CANCEL, ride);
            default -> {
                // ASSIGNED: as accepted
            }
        }
        assertThat(rides.violations(city.id())).as("set up %s", setup).isEmpty();
        return ride;
    }

    private void done(Ride ride, Command... commands) {
        for (Command command : commands) {
            done(command, ride);
        }
    }

    private void done(Command command, Ride ride) {
        HttpResponse<String> response = send(command, ride);
        assertThat(response.statusCode()).as("%s: %s", command, response.body()).isEqualTo(200);
    }

    private HttpResponse<String> send(Command command, Ride ride) {
        String path = command.path.replace("{ride_id}", ride.id().toString());
        return switch (command) {
            case ARRIVE, NO_SHOW, COMPLETE, DRIVER_CANCEL -> post(ride.driver().authorization(), path, "{}");
            case START -> post(ride.driver().authorization(), path, "{\"pin\": \"" + ride.pin() + "\"}");
            case RIDER_CANCEL -> post(ride.rider().authorization(), path, "{}");
            case OPS_CANCEL -> post(ride.ops().authorization(), path, "{\"reason\": \"matrix\"}");
        };
    }

    private HttpResponse<String> post(String authorization, String path, String body) {
        return postJson(path, Map.of("Authorization", authorization, Idempotency.HEADER, UUID.randomUUID().toString()),
                body);
    }

    private static String outcome(HttpResponse<String> response, JsonNode body) {
        return response.statusCode() < 400 ? String.valueOf(response.statusCode())
                : response.statusCode() + " " + body.path("code").asString();
    }

    /** What a command could change: the ride, its log, events and flags, and its parties' availability. */
    private String state(Ride ride) {
        return jdbc.sql("""
                        SELECT r.status || ' v' || r.version || ' pin attempts ' || r.pin_attempts
                               || ' transitions ' || (SELECT count(*) FROM ride.transitions t WHERE t.ride_id = r.id)
                               || ' events ' || (SELECT count(*) FROM platform.outbox o WHERE o.aggregate_id = r.id)
                               || ' flags ' || (SELECT count(*) FROM ride.flags f WHERE f.ride_id = r.id)
                               || ' driver ' || coalesce((SELECT a.status || ' v' || a.version
                                                          FROM dispatch.driver_availability a
                                                          WHERE a.driver_id = :driver), 'never online')
                        FROM ride.rides r WHERE r.id = :id
                        """)
                .param("id", ride.id())
                .param("driver", ride.driver().id())
                .query(String.class)
                .single();
    }

    private RideStatus status(Ride ride) {
        return RideStatus.valueOf(rides.rideStatus(ride.id()));
    }

    private int version(Ride ride) {
        return jdbc.sql("SELECT version FROM ride.rides WHERE id = :id").param("id", ride.id()).query(Integer.class)
                .single();
    }

    /** {@code driver} is the ride's driver, current or former, or a stranger to it; {@code pin} the right one. */
    private record Ride(UUID id, TestCity city, TestUser rider, TestDriver driver, TestUser ops, String pin) {
    }
}
