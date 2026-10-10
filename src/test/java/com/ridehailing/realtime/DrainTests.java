package com.ridehailing.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.ridehailing.RideHailingApplication;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.outbox.EventDelivery;
import com.ridehailing.platform.timers.TimerFiring;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.support.Postgis;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestRides.AssignedRide;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.Valkeys;
import com.ridehailing.support.WsClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.LifecycleProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * LLD §14.5, §17.1, NFR-12: two nodes on Valkey. Draining the first tells every client to reconnect; each takes a
 * ticket from the second and connects there, and every ride then runs to completion with its pushes arriving there.
 */
class DrainTests {

    private static final Duration SPREAD = Duration.ofSeconds(1);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(20);
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<ConfigurableApplicationContext> nodes = new ArrayList<>();
    private final List<WsClient> clients = new ArrayList<>();

    @AfterEach
    void stopEverything() {
        clients.forEach(WsClient::close);
        nodes.forEach(ConfigurableApplicationContext::close);
    }

    @Test
    void pausingAndRestartingANodeDoesNotDrainIt() throws Exception {
        String signingKey = new ECKeyGenerator(Curve.P_256).keyID("pause-test").generate().toJSONString();
        ConfigurableApplicationContext node = node(signingKey);
        ApplicationAvailability availability = node.getBean(ApplicationAvailability.class);
        LifecycleProcessor lifecycle = node.getBean(LifecycleProcessor.class);
        assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);

        lifecycle.onPause();
        try {
            assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
        } finally {
            lifecycle.onRestart();
        }

        assertThat(readiness(port(node))).isEqualTo(200);
        TestUsers.TestUser rider = node.getBean(TestRides.class).rider("Asha");
        connect(port(node), ticket(port(node), rider.authorization()));
    }

    @Test
    void drainingANodeMovesEveryClientToAnotherWithoutLosingARide() throws Exception {
        String signingKey = new ECKeyGenerator(Curve.P_256).keyID("drain-test").generate().toJSONString();
        ConfigurableApplicationContext first = node(signingKey);
        ConfigurableApplicationContext second = node(signingKey);
        int firstPort = port(first);
        int secondPort = port(second);
        TestRides rides = first.getBean(TestRides.class);
        TestCity city = first.getBean(TestCities.class).create("MINI");
        first.getBean(TestPrices.class).price(city.id(), "MINI");
        List<Party> parties = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            GeoPoint pickup = city.at(0.05 + 0.1 * i, 0.1);
            AssignedRide ride = rides.assigned(city, pickup, new GeoPoint(pickup.lat() + 0.001, pickup.lon()));
            parties.add(new Party(ride, ride.rider().authorization()));
            parties.add(new Party(ride, ride.driver().authorization()));
        }
        List<WsClient> onFirst = new ArrayList<>();
        for (Party party : parties) {
            onFirst.add(connect(firstPort, ticket(firstPort, party.authorization())));
        }
        assertThat(readiness(firstPort)).isEqualTo(200);

        CompletableFuture<Void> drained = CompletableFuture.runAsync(first::close);
        List<WsClient> onSecond = new ArrayList<>();
        for (int i = 0; i < parties.size(); i++) {
            JsonNode reconnect = onFirst.get(i).await("reconnect");
            assertThat(reconnect.get("after_ms").asLong()).isBetween(0L, SPREAD.toMillis());
            if (i == 0) {
                assertThat(WsClient.refusal(firstPort, ticket(secondPort, parties.get(i).authorization())))
                        .as("the draining node takes no one new").isEqualTo(503);
                assertThat(readiness(firstPort)).as("so the load balancer sends no one").isEqualTo(503);
            }
            onSecond.add(connect(secondPort, ticket(secondPort, parties.get(i).authorization())));
            onFirst.get(i).close();
        }
        drained.get(DRAIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        for (int i = 0; i < parties.size(); i += 2) {
            AssignedRide ride = parties.get(i).ride();
            String driver = parties.get(i + 1).authorization();
            command(secondPort, driver, ride, "arrive", "{}");
            command(secondPort, driver, ride, "start", "{\"pin\": \"" + ride.pin() + "\"}");
            command(secondPort, driver, ride, "complete", "{}");
        }
        for (int i = 0; i < parties.size(); i++) {
            WsClient client = onSecond.get(i);
            String rideId = parties.get(i).ride().id().toString();
            for (String status : List.of("DRIVER_ARRIVED", "IN_TRIP", "COMPLETED")) {
                assertThat(client.await("ride_status", "status", status).get("ride_id").asString())
                        .as("%s on the second node", status).isEqualTo(rideId);
            }
        }
    }

    /** All roles on Valkey and a database of the test's own, with one signing key so either node takes tokens. */
    private ConfigurableApplicationContext node(String signingKey) {
        ConfigurableApplicationContext node = new SpringApplicationBuilder(RideHailingApplication.class)
                .sources(TestUsers.class, TestCities.class, TestPrices.class, TestDrivers.class, TestRides.class,
                        TimerFiring.class, EventDelivery.class)
                .profiles("test")
                .run("--server.port=0", "--management.server.port=0", "--ride.workers.autostart=false",
                        "--spring.datasource.url=" + Postgis.shared().getJdbcUrl(),
                        "--spring.datasource.username=" + Postgis.shared().getUsername(),
                        "--spring.datasource.password=" + Postgis.shared().getPassword(),
                        "--spring.datasource.hikari.jdbc-url=" + Postgis.database("drain"),
                        "--ride.location.store=valkey", "--ride.valkey.uri=" + Valkeys.uri(),
                        "--ride.valkey.timeouts.query=5s", "--ride.valkey.timeouts.mirror=5s",
                        "--ride.valkey.timeouts.update=5s", "--ride.valkey.timeouts.other=5s",
                        "--ride.security.jwt.keys[0]=" + signingKey,
                        "--ride.realtime.drain-spread=" + SPREAD.toMillis() + "ms",
                        "--ride.realtime.drain-timeout=" + DRAIN_TIMEOUT.toMillis() + "ms");
        nodes.add(node);
        return node;
    }

    private static int port(ConfigurableApplicationContext node) {
        return ((WebServerApplicationContext) node).getWebServer().getPort();
    }

    private WsClient connect(int port, String ticket) {
        WsClient client = WsClient.connect(port, ticket);
        clients.add(client);
        return client;
    }

    private static String ticket(int port, String authorization) {
        HttpResponse<String> issued = post(port, "/v1/realtime/tickets", authorization, null);
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(201);
        return JSON.readTree(issued.body()).get("ticket").asString();
    }

    private static void command(int port, String authorization, AssignedRide ride, String action, String body) {
        HttpResponse<String> answer = post(port, "/v1/rides/" + ride.id() + "/" + action, authorization, body);
        assertThat(answer.statusCode()).as("%s: %s", action, answer.body()).isEqualTo(200);
    }

    /** The probe path on the API port, which outlives the management server while the node drains. */
    private static int readiness(int port) {
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/readyz")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            throw new AssertionError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static HttpResponse<String> post(int port, String path, String authorization, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", authorization)
                .header(Idempotency.HEADER, UUID.randomUUID().toString());
        if (body == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AssertionError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** A rider or a driver of a ride; each ride's rider comes just before its driver. */
    private record Party(AssignedRide ride, String authorization) {
    }
}
