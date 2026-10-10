package com.ridehailing.ride;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.location.LiveIndex.LocationUpdate;
import com.ridehailing.location.LocationIngestion;
import com.ridehailing.location.trips.TripPointBuffer;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.Eventually;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** LLD §9.6, §9.8: live and replayed updates make a ride's route, which its rider, its driver and operations read. */
class TripRouteTests extends IntegrationTest {

    private static final String ROUTE = "/v1/rides/{ride_id}/route";
    private static final String LOCATION = "/v1/drivers/me/location";

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
    private LocationIngestion ingestion;

    @Autowired
    private TripPointBuffer tripPoints;

    @Autowired
    private RideQueries rideQueries;

    @Autowired
    private JdbcClient jdbc;

    private TestCity city;
    private AssignedRide ride;
    private TestDriver driver;
    private Instant assignedAt;

    @BeforeEach
    void setUp() {
        city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        ride = rides.assigned(city, city.at(0.1, 0.1), step(0));
        driver = ride.driver();
        assignedAt = jdbc.sql("SELECT assigned_at FROM ride.rides WHERE id = :id").param("id", ride.id())
                .query(OffsetDateTime.class).single().toInstant();
    }

    @Test
    void theDriversLiveUpdatesDuringTheRideMakeItsRoute() {
        live(update(2, 1, 5), update(3, 2, 5), update(5, 3, 150));
        live(update(4, 3, 5));
        tripPoints.flush();

        JsonNode route = assertAnswered("GET", ROUTE, getAs(ride.rider().authorization(), path(ride.id())), 200);

        assertThat(route.get("ride_id").asString()).isEqualTo(ride.id().toString());
        assertThat(route.get("thinned").asBoolean()).isFalse();
        assertThat(seqs(route)).as("4 came after 5, so the index didn't apply it").containsExactly(2L, 3L, 5L);
        assertThat(route.get("points").get(0).get("lat").asDouble()).isEqualTo(step(1).lat());
        assertThat(flags()).as("the last was poor accuracy").containsExactly("2:0", "3:0", "5:1");
        assertThat(assertAnswered("GET", ROUTE, getAs(driver.authorization(), path(ride.id())), 200))
                .isEqualTo(route);
    }

    @Test
    void aPointStoredOnTwoDaysIsInTheRouteOnceAsItFirstArrived() {
        Instant yesterday = Instant.now().minus(Duration.ofDays(1));
        store(yesterday, 7, step(1));
        store(yesterday.plus(Duration.ofDays(1)), 7, step(2));

        JsonNode route = assertAnswered("GET", ROUTE, getAs(ride.rider().authorization(), path(ride.id())), 200);

        assertThat(route.get("points").valueStream()).singleElement().satisfies(point -> {
            assertThat(point.get("seq").asLong()).isEqualTo(7);
            assertThat(point.get("lat").asDouble()).isEqualTo(step(1).lat());
        });
    }

    @Test
    void aReplaySentTwiceLeavesEachSequenceNumberOnceAndMarksTheReplayedPoints() {
        live(update(2, 1, 5), update(3, 2, 5), update(4, 3, 5));
        tripPoints.flush();
        String replay = batch(replayed(3, 2, assignedAt.plusSeconds(3)), replayed(4, 3, assignedAt.plusSeconds(4)),
                replayed(5, 4, assignedAt.plusSeconds(5)), replayed(6, 5, assignedAt.plusSeconds(6)),
                replayed(7, 6, assignedAt.minusSeconds(60)));

        JsonNode first = assertAnswered("POST", LOCATION, call("POST", driver.authorization(), LOCATION, replay), 200);
        // The second waits out the limit of one request a second.
        Eventually.within(Duration.ofSeconds(5), () ->
                assertAnswered("POST", LOCATION, call("POST", driver.authorization(), LOCATION, replay), 200));
        tripPoints.flush();

        assertThat(first.get("applied").asInt()).isEqualTo(3);
        assertThat(first.get("stale").asInt()).isEqualTo(2);
        assertThat(seqs(assertAnswered("GET", ROUTE, getAs(ride.rider().authorization(), path(ride.id())), 200)))
                .as("7 was recorded before the ride was assigned").containsExactly(2L, 3L, 4L, 5L, 6L);
        assertThat(flags()).containsExactly("2:0", "3:0", "4:0", "5:4", "6:4");
    }

    @Test
    void aDriversRideAtATimeRunsFromTheAssignmentToTheEnd() {
        drive("arrive", "{}");
        drive("start", "{\"pin\": \"" + ride.pin() + "\"}");
        drive("complete", "{}");
        Instant endedAt = jdbc.sql("SELECT ended_at FROM ride.rides WHERE id = :id").param("id", ride.id())
                .query(OffsetDateTime.class).single().toInstant();

        assertThat(rideQueries.rideOfDriverAt(driver.id(), assignedAt.minusMillis(1))).isEmpty();
        assertThat(rideQueries.rideOfDriverAt(driver.id(), assignedAt)).contains(ride.id());
        assertThat(rideQueries.rideOfDriverAt(driver.id(), endedAt)).contains(ride.id());
        assertThat(rideQueries.rideOfDriverAt(driver.id(), endedAt.plusMillis(1))).isEmpty();
        assertThat(rideQueries.rideOfDriverAt(ride.rider().id(), assignedAt)).isEmpty();
    }

    @Test
    void operationsReadAnyRouteAndEachOfTheirReadsIsAudited() {
        TestUser ops = users.create(UserRole.OPS);

        assertAnswered("GET", ROUTE, getAs(ops.authorization(), path(ride.id())), 200);
        assertAnswered("GET", ROUTE, getAs(ops.authorization(), path(ride.id())), 200);
        assertAnswered("GET", ROUTE, getAs(ride.rider().authorization(), path(ride.id())), 200);

        assertThat(jdbc.sql("""
                        SELECT actor_type || ' ' || actor_id FROM audit.audit_log
                        WHERE action = 'route.read' AND entity_type = 'ride' AND entity_id = :id
                        """).param("id", ride.id().toString()).query(String.class).list())
                .containsExactly("OPS " + ops.id(), "OPS " + ops.id());
    }

    @Test
    void anyoneElseIsRefused() {
        TestDriver otherDriver = drivers.create(city, "MINI");

        assertProblem("GET", ROUTE, getAs(rides.rider("Other").authorization(), path(ride.id())), 404, "NOT_FOUND");
        assertProblem("GET", ROUTE, getAs(otherDriver.authorization(), path(ride.id())), 404, "NOT_FOUND");
        assertProblem("GET", ROUTE, getAs(users.create(UserRole.OPS).authorization(), path(UUID.randomUUID())), 404,
                "NOT_FOUND");
        assertProblem("GET", ROUTE, getAs(users.create(UserRole.ADMIN).authorization(), path(ride.id())), 403,
                "FORBIDDEN");
        assertProblem("GET", ROUTE, call("GET", null, path(ride.id()), null), 401, "UNAUTHENTICATED");
    }

    /** About 11 m north of the driver's first position for each step, so no update is an implausible jump. */
    private GeoPoint step(int n) {
        return city.at(0.1, 0.101 + 0.0001 * n);
    }

    private LocationUpdate update(long seq, int step, double accuracyM) {
        return new LocationUpdate(seq, step(step), accuracyM, 90.0, 7.5, Instant.now());
    }

    /** Straight into the ingestion, as the WebSocket sends them, so the HTTP rate limit doesn't apply. */
    private void live(LocationUpdate... updates) {
        ingestion.accept(city.id(), "MINI", driver.id(), Arrays.asList(updates));
    }

    private String replayed(long seq, int step, Instant deviceTime) {
        return """
                {"seq": %d, "lat": %s, "lon": %s, "accuracy_m": 5, "device_time": "%s", "replay": true}\
                """.formatted(seq, step(step).lat(), step(step).lon(), deviceTime);
    }

    private static String batch(String... updates) {
        return "{\"updates\": [" + String.join(", ", updates) + "]}";
    }

    private void drive(String action, String body) {
        assertThat(postJson("/v1/rides/" + ride.id() + "/" + action, Map.of("Authorization", driver.authorization(),
                Idempotency.HEADER, UUID.randomUUID().toString()), body).statusCode()).isEqualTo(200);
    }

    private static String path(UUID rideId) {
        return "/v1/rides/" + rideId + "/route";
    }

    private static List<Long> seqs(JsonNode route) {
        return route.get("points").valueStream().map(point -> point.get("seq").asLong()).toList();
    }

    private List<String> flags() {
        return jdbc.sql("SELECT seq || ':' || flags FROM location.trip_points WHERE ride_id = :id ORDER BY seq")
                .param("id", ride.id()).query(String.class).list();
    }

    /** A point as the buffer writes it, on the UTC day it arrived. */
    private void store(Instant receivedAt, long seq, GeoPoint at) {
        jdbc.sql("""
                        INSERT INTO location.trip_points (received_day, ride_id, seq, driver_id, received_at, lat, lon)
                        VALUES (:day, :ride, :seq, :driver, :at, :lat, :lon)
                        """)
                .param("day", LocalDate.ofInstant(receivedAt, ZoneOffset.UTC))
                .param("ride", ride.id())
                .param("seq", seq)
                .param("driver", driver.id())
                .param("at", receivedAt.atOffset(ZoneOffset.UTC))
                .param("lat", at.lat())
                .param("lon", at.lon())
                .update();
    }
}
