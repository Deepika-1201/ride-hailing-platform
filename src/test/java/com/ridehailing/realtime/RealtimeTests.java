package com.ridehailing.realtime;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.location.trips.TripPointBuffer;
import com.ridehailing.notification.NotificationApi;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.PushBus;
import com.ridehailing.ride.RideView;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.Eventually;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestRides.AssignedRide;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import com.ridehailing.support.WsClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** LLD §14: tickets, the handshake, pushes to each party, messages in, and the operations map, over real sockets. */
class RealtimeTests extends IntegrationTest {

    private static final String TICKETS = "/v1/realtime/tickets";

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestRides rides;

    @Autowired
    private TestUsers users;

    @Autowired
    private TripPointBuffer tripPoints;

    @Autowired
    private NotificationApi notifications;

    @Autowired
    private PushBus push;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcClient jdbc;

    private final List<WsClient> clients = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong(1);
    private TestCity city;

    @BeforeEach
    void setUp() {
        city = cities.create("MINI");
        prices.price(city.id(), "MINI");
    }

    @AfterEach
    void closeClients() {
        clients.forEach(WsClient::close);
    }

    @Test
    void aTicketOpensOneConnection() {
        TestUser rider = rides.rider("Asha");
        JsonNode ticket = assertAnswered("POST", TICKETS, call("POST", rider.authorization(), TICKETS, null), 201);
        double closedByClients = closes("client");

        assertThat(ticket.get("url").asString()).isEqualTo("ws://localhost:8080/ws");
        assertThat(Instant.parse(ticket.get("expires_at").asString()))
                .isBetween(Instant.now().plusSeconds(50), Instant.now().plusSeconds(61));
        WsClient socket = connect(ticket.get("ticket").asString());
        Eventually.within(Duration.ofSeconds(5), () -> assertThat(connections("rider")).isPositive());

        assertThat(WsClient.refusal(port, ticket.get("ticket").asString())).as("used once already").isEqualTo(401);
        assertThat(WsClient.refusal(port, null)).isEqualTo(401);
        assertThat(WsClient.refusal(port, "not-a-ticket")).isEqualTo(401);
        socket.close();
        Eventually.within(Duration.ofSeconds(5), () -> assertThat(closes("client"))
                .isGreaterThanOrEqualTo(closedByClients + 1));
    }

    @Test
    void ticketsAreForRidersDriversAndOperationsTenAMinute() {
        TestUser ops = users.create(UserRole.OPS);

        for (int i = 0; i < 10; i++) {
            assertAnswered("POST", TICKETS, call("POST", ops.authorization(), TICKETS, null), 201);
        }

        assertProblem("POST", TICKETS, call("POST", ops.authorization(), TICKETS, null), 429, "RATE_LIMITED");
        assertProblem("POST", TICKETS, call("POST", users.create(UserRole.ADMIN).authorization(), TICKETS, null), 403,
                "FORBIDDEN");
        assertProblem("POST", TICKETS, call("POST", null, TICKETS, null), 401, "UNAUTHENTICATED");
    }

    @Test
    void aDriverHearsOfTheirOfferAndHowItEnded() {
        TestDriver driver = rides.onlineAt(city, "MINI", step(0));
        WsClient socket = connect(ticket(driver.authorization()));
        RideView ride = rides.book(rides.rider("Asha").id(), city, city.at(0.1, 0.1), "MINI");
        rides.onlyDueIn(city.id());
        rides.search();
        UUID offerId = rides.pendingOffer(ride.id());

        JsonNode offer = socket.await("offer");
        assertThat(offer.get("offer_id").asString()).isEqualTo(offerId.toString());
        assertThat(offer.get("ride_id").asString()).isEqualTo(ride.id().toString());
        assertThat(offer.get("rider").get("first_name").asString()).isEqualTo("Asha");
        assertThat(offer.get("expires_in_ms").asLong()).isBetween(1L, 15_000L);

        socket.send("{\"type\": \"offer_seen\", \"offer_id\": \"" + offerId + "\"}");
        Eventually.within(Duration.ofSeconds(5), () -> assertThat(jdbc.sql(
                "SELECT seen_at IS NOT NULL FROM dispatch.offers WHERE id = :id").param("id", offerId)
                .query(Boolean.class).single()).isTrue());

        rides.fire("OFFER_EXPIRY", offerId);
        assertThat(socket.await("offer_withdrawn").get("reason").asString()).isEqualTo("EXPIRED");
    }

    @Test
    void aDriverHearsWhenOperationsTakeThemOffline() {
        TestDriver driver = rides.onlineAt(city, "MINI", step(0));
        WsClient socket = connect(ticket(driver.authorization()));

        assertThat(postJson("/v1/ops/drivers/" + driver.id() + "/suspend", Map.of("Authorization",
                users.create(UserRole.OPS).authorization(), Idempotency.HEADER, UUID.randomUUID().toString()),
                "{\"reason\": \"Documents expired\"}").statusCode()).isEqualTo(200);

        JsonNode status = socket.await("driver_status");
        assertThat(status.get("status").asString()).isEqualTo("OFFLINE");
        assertThat(status.get("reason").asString()).isEqualTo("SUSPENDED");
    }

    @Test
    void aRiderFollowsTheirRideFromAssignmentAndSeesTheDriverComing() {
        TestUser rider = rides.rider("Asha");
        WsClient riderSocket = connect(ticket(rider.authorization()));
        AssignedRide ride = rides.assigned(city, city.at(0.1, 0.1), step(0), rider);

        JsonNode assigned = riderSocket.await("ride_status", "status", "DRIVER_ASSIGNED");
        assertThat(assigned.get("driver").get("first_name").asString()).isEqualTo("Test");
        assertThat(assigned.has("vehicle")).isTrue();
        WsClient driverSocket = connect(ticket(ride.driver().authorization()));
        JsonNode position = drive(driverSocket, riderSocket, ride);

        assertThat(position.get("ride_id").asString()).isEqualTo(ride.id().toString());
        assertThat(position.get("eta_s").asInt()).isNotNegative();
        tripPoints.flush();
        assertThat(jdbc.sql("SELECT count(*) FROM location.trip_points WHERE ride_id = :id AND seq = :seq")
                .param("id", ride.id()).param("seq", position.get("seq").asLong()).query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM notification.notifications
                        WHERE ride_id = :id AND kind = 'DRIVER_ARRIVING' AND recipient_id = :rider
                        """).param("id", ride.id()).param("rider", rider.id()).query(Long.class).single())
                .as("the pickup is a minute away, so the rider is told before the position goes").isEqualTo(1);
        notifications.driverArriving(ride.id(), rider.id());
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM notification.notifications WHERE ride_id = :id AND kind = 'DRIVER_ARRIVING'
                        """).param("id", ride.id()).query(Long.class).single())
                .as("once per ride, whichever node tells it again").isEqualTo(1);
    }

    @Test
    void aPositionTheQualityRulesSetAsideIsntPushed() {
        AssignedRide ride = rides.assigned(city, city.at(0.1, 0.1), step(0));
        WsClient riderSocket = connect(ticket(ride.rider().authorization()));
        WsClient driverSocket = connect(ticket(ride.driver().authorization()));
        Eventually.within(Duration.ofSeconds(5), () -> {
            push.publish(PushBus.rideChannel(ride.id()), Map.of("type", "driver_position", "ride_id",
                    ride.id().toString(), "lat", 0.0, "lon", 0.0, "seq", 0));
            assertThat(riderSocket.messages("driver_position")).as("the rider follows the ride").isNotEmpty();
        });

        driverSocket.send(location(1_000, step(1), 150));
        Eventually.within(Duration.ofSeconds(10), () -> {
            driverSocket.send(location(1_001, step(1), 5));
            Eventually.within(Duration.ofMillis(1_100), () -> assertThat(positions(riderSocket)).contains(1_001L));
        });

        assertThat(positions(riderSocket)).as("poor accuracy is a sign of life, not a position")
                .doesNotContain(1_000L);
    }

    @Test
    void aRiderWhoConnectsDuringTheirRideFollowsIt() {
        AssignedRide ride = rides.assigned(city, city.at(0.1, 0.1), step(0));
        WsClient riderSocket = connect(ticket(ride.rider().authorization()));
        WsClient driverSocket = connect(ticket(ride.driver().authorization()));

        assertThat(drive(driverSocket, riderSocket, ride).get("ride_id").asString()).isEqualTo(ride.id().toString());
    }

    @Test
    void messagesThatBreakTheRulesGetErrors() {
        TestDriver driver = rides.onlineAt(city, "MINI", step(0));
        WsClient socket = connect(ticket(driver.authorization()));
        WsClient riderSocket = connect(ticket(rides.rider("Asha").authorization()));

        socket.send("not json");
        socket.send("{\"type\": \"dance\"}");
        socket.send("{\"type\": \"location\", \"seq\": 2}");
        socket.send("{\"type\": \"offer_seen\"}");
        socket.send(viewport());
        socket.send(location(2, step(1)));
        socket.send(location(3, step(2)));
        riderSocket.send(location(1, step(1)));

        assertThat(socket.await("error", 6).stream().map(error -> error.get("code").asString()))
                .containsExactly("INVALID_MESSAGE", "INVALID_MESSAGE", "INVALID_MESSAGE", "INVALID_MESSAGE",
                        "FORBIDDEN", "RATE_LIMITED");
        assertThat(riderSocket.await("error").get("code").asString()).isEqualTo("FORBIDDEN");
        assertThat(socket.isOpen()).isTrue();
    }

    @Test
    void anOversizedOrBinaryFrameClosesTheConnection() {
        TestUser rider = rides.rider("Asha");
        WsClient oversized = connect(ticket(rider.authorization()));
        WsClient binary = connect(ticket(rider.authorization()));

        oversized.send("{\"type\": \"offer_seen\", \"pad\": \"" + "x".repeat(1_100) + "\"}");
        binary.sendBinary(new byte[] {1, 2, 3});

        assertThat(oversized.awaitClose()).isEqualTo(1009);
        assertThat(binary.awaitClose()).isEqualTo(1003);
    }

    @Test
    void operationsSeeTheDriversInTheirViewport() {
        TestDriver driver = rides.onlineAt(city, "MINI", city.at(0.2, 0.2));
        WsClient ops = connect(ticket(users.create(UserRole.OPS).authorization()));

        ops.send(viewport());

        JsonNode snapshot = ops.await("ops_snapshot");
        assertThat(snapshot.get("city_id").asString()).isEqualTo(city.id());
        assertThat(snapshot.get("truncated").asBoolean()).isFalse();
        assertThat(snapshot.get("drivers").valueStream()).singleElement().satisfies(seen -> {
            assertThat(seen.get("driver_id").asString()).isEqualTo(driver.id().toString());
            assertThat(seen.get("status").asString()).isEqualTo("AVAILABLE");
            assertThat(seen.get("category").asString()).isEqualTo("MINI");
            assertThat(seen.get("stale").asBoolean()).isFalse();
        });
    }

    /**
     * Sends the driver's locations, one a second as the limit allows, until the rider hears one: the rider's session
     * follows the ride's channel on a thread of its own.
     */
    private JsonNode drive(WsClient driverSocket, WsClient riderSocket, AssignedRide ride) {
        Eventually.within(Duration.ofSeconds(10), () -> {
            if (riderSocket.messages("driver_position").isEmpty()) {
                driverSocket.send(location(seq.incrementAndGet(), step(seq.get())));
                Eventually.within(Duration.ofMillis(1_100), () ->
                        assertThat(riderSocket.messages("driver_position")).isNotEmpty());
            }
        });
        assertThat(driverSocket.messages("error")).as("no update was refused").isEmpty();
        return riderSocket.messages("driver_position").getFirst();
    }

    /** About 11 m north of the last for each step, so no update is an implausible jump. */
    private GeoPoint step(long n) {
        return city.at(0.1, 0.101 + 0.0001 * n);
    }

    private static String location(long seq, GeoPoint at) {
        return location(seq, at, 5);
    }

    private static String location(long seq, GeoPoint at, double accuracyM) {
        return """
                {"type": "location", "seq": %d, "lat": %s, "lon": %s, "accuracy_m": %s, "heading_deg": 0,
                 "speed_mps": 8, "device_time": "%s"}""".formatted(seq, at.lat(), at.lon(), accuracyM, Instant.now());
    }

    private static List<Long> positions(WsClient rider) {
        return rider.messages("driver_position").stream().map(position -> position.get("seq").asLong()).toList();
    }

    private String viewport() {
        return """
                {"type": "ops_viewport", "city_id": "%s",
                 "bbox": {"min_lat": %s, "min_lon": %s, "max_lat": %s, "max_lon": %s}}"""
                .formatted(city.id(), city.lat(), city.lon(), city.lat() + TestCities.SIZE, city.lon() + TestCities.SIZE);
    }

    private String ticket(String authorization) {
        return assertAnswered("POST", TICKETS, call("POST", authorization, TICKETS, null), 201).get("ticket")
                .asString();
    }

    private WsClient connect(String ticket) {
        WsClient client = WsClient.connect(port, ticket);
        clients.add(client);
        return client;
    }

    private double connections(String kind) {
        return meters.get("websocket.connections").tag("kind", kind).gauge().value();
    }

    private double closes(String reason) {
        Counter counter = meters.find("websocket.closes").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }
}
