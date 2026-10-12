package com.ridehailing.geography.app;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static com.ridehailing.support.EventContract.outboxEvents;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.geography.GeographyApi;
import com.ridehailing.geography.app.CityAdministration.NewCity;
import com.ridehailing.geography.db.CategoryRepository.Settings;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.Valkey;
import com.ridehailing.ride.RideView;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.EventContract;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import com.ridehailing.support.ValkeyIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestPropertySource(properties = {"ride.routing.osrm.enabled=true", "ride.routing.osrm.cache-timeout=100ms",
    "ride.routing.osrm.timeout=2s"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OsrmQuoteTests extends ValkeyIntegrationTest {

    private static final String CITY = TestCities.newId();
    private static final Caller ADMIN = new Caller(Ids.newId(), Set.of(UserRole.ADMIN));
    private static final GeoPoint PICKUP = new GeoPoint(12.95, 77.5);
    private static final GeoPoint DROPOFF = new GeoPoint(13.0, 77.65);
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicInteger STATUS = new AtomicInteger(200);
    private static final AtomicReference<String> BODY = new AtomicReference<>();
    private static final AtomicInteger TABLE_CALLS = new AtomicInteger();
    private static final AtomicInteger TABLE_STATUS = new AtomicInteger(200);
    private static final AtomicReference<String> TABLE_BODY = new AtomicReference<>();
    private static final AtomicReference<Runnable> TABLE_ACTION = new AtomicReference<>(() -> { });
    private static final HttpServer SERVER = startServer();
    private static final String BASE_URL = "http://127.0.0.1:" + SERVER.getAddress().getPort();

    @Autowired
    GeographyApi geography;

    @Autowired
    CityAdministration cities;

    @Autowired
    CategoryAdministration categories;

    @Autowired
    TestCities otherCities;

    @Autowired
    TestPrices prices;

    @Autowired
    TestUsers users;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Valkey valkey;

    @Autowired
    H3Zones zones;

    @Autowired
    JsonMapper json;

    @Autowired
    TestRides rides;

    @Autowired
    DispatchApi dispatch;

    private String rider;
    private final List<TestDriver> testDrivers = new ArrayList<>();
    private RideView booked;
    private TestUser bookingRider;

    @DynamicPropertySource
    static void routing(DynamicPropertyRegistry registry) {
        registry.add("ride.routing.osrm.base-url", () -> BASE_URL);
        registry.add("ride.routing.osrm.cities", () -> CITY);
    }

    @BeforeEach
    void prepare() {
        if (geography.city(CITY).isEmpty()) {
            cities.create(ADMIN, new NewCity(CITY, "OSRM routing test", "Asia/Kolkata", "INR", TestCities.polygon(77.3, 12.7, 0.4)));
            cities.replaceServiceArea(ADMIN, CITY, TestCities.multiPolygon(77.3, 12.7, 0.4));
            categories.put(ADMIN, new Settings(CITY, "MINI", true, 15, 180, 2000, 1000, 6000, "nearest", 0), null);
        }
        prices.price(CITY, "MINI");
        jdbc.sql("UPDATE geography.city_categories SET ranker = 'nearest' WHERE city_id = :city AND category = 'MINI'")
            .param("city", CITY).update();
        rider = users.create(UserRole.RIDER).authorization();
        valkey.await("test-route-clear", WAIT, valkey.commands().del(key()));
        CALLS.set(0);
        STATUS.set(200);
        BODY.set("{\"code\":\"Ok\",\"routes\":[{\"distance\":6656.6,\"duration\":688.3}]}");
        TABLE_CALLS.set(0);
        TABLE_STATUS.set(200);
        TABLE_BODY.set("{\"code\":\"Ok\",\"durations\":[[95.75],[20.25]]}");
        TABLE_ACTION.set(() -> { });
    }

    @AfterEach
    void closeOwnedRidesAndDrivers() {
        if (booked != null) {
            assertThat(postJson("/v1/rides/" + booked.id() + "/cancel", Map.of("Authorization", bookingRider.authorization(),
                    Idempotency.HEADER, UUID.randomUUID().toString()), "{}").statusCode()).isEqualTo(200);
        }
        for (TestDriver driver : testDrivers) {
            TestRides.asApi(() -> dispatch.goOffline(driver.id()));
        }
    }

    @AfterAll
    static void closeServer() {
        SERVER.stop(0);
        EXECUTOR.close();
    }

    @Test
    void quotesUseRoadMeasurementsAndCacheThemWithProvenanceAndTtl() {
        JsonNode first = assertAnswered("POST", "/v1/quotes", quote(PICKUP, DROPOFF), 201);
        JsonNode cached = assertAnswered("POST", "/v1/quotes", quote(PICKUP, DROPOFF), 201);

        assertThat(first.get("distance_m").asInt()).isEqualTo(6657);
        assertThat(first.get("duration_s").asInt()).isEqualTo(688);
        assertThat(cached.get("fare")).isEqualTo(first.get("fare"));
        assertThat(source(first)).isEqualTo("OSRM");
        assertThat(source(cached)).isEqualTo("OSRM");
        assertThat(CALLS).hasValue(1);
        assertThat(valkey.await("test-route-ttl", WAIT, valkey.commands().ttl(key()))).isBetween(590L, 600L);
        String metrics = get(managementPort, "/actuator/prometheus").body();
        assertThat(metrics).contains("routing_requests_total", "routing_cache_total", "outcome=\"hit\"", "outcome=\"fallback\"");
    }

    @Test
    void failedRoutingIsExplicitAndNeverPoisonsTheCache() {
        STATUS.set(503);
        BODY.set("{\"code\":\"Error\"}");
        JsonNode fallback = assertAnswered("POST", "/v1/quotes", quote(PICKUP, DROPOFF), 201);
        assertThat(source(fallback)).isEqualTo("MOCK_FALLBACK");
        assertThat(valkey.await("test-route-read", WAIT, valkey.commands().get(key()))).isNull();

        STATUS.set(200);
        BODY.set("{\"code\":\"Ok\",\"routes\":[{\"distance\":7654,\"duration\":901}]}");
        JsonNode recovered = assertAnswered("POST", "/v1/quotes", quote(PICKUP, DROPOFF), 201);
        assertThat(source(recovered)).isEqualTo("OSRM");
        assertThat(recovered.get("distance_m").asInt()).isEqualTo(7654);
        assertThat(CALLS).hasValue(2);
    }

    @Test
    void noRoadRouteRemainsTheDocumentedQuoteRefusal() {
        STATUS.set(400);
        BODY.set("{\"code\":\"NoRoute\"}");

        assertProblem("POST", "/v1/quotes", quote(PICKUP, DROPOFF), 422, "ROUTE_NOT_FOUND");
        assertThat(valkey.await("test-route-read", WAIT, valkey.commands().get(key()))).isNull();
    }

    @Test
    void anExpiredCacheEntryReloadsTheRoadResult() {
        assertAnswered("POST", "/v1/quotes", quote(PICKUP, DROPOFF), 201);
        assertThat(valkey.await("test-route-expire", WAIT, valkey.commands().expire(key(), 0))).isTrue();
        BODY.set("{\"code\":\"Ok\",\"routes\":[{\"distance\":7777,\"duration\":888}]}");

        JsonNode refreshed = assertAnswered("POST", "/v1/quotes", quote(PICKUP, DROPOFF), 201);

        assertThat(refreshed.get("distance_m").asInt()).isEqualTo(7777);
        assertThat(CALLS).hasValue(2);
    }

    @Test
    void citiesThatDidNotOptInKeepTheExistingMockRoute() {
        var other = otherCities.create("MINI");
        prices.price(other.id(), "MINI");

        JsonNode quote = assertAnswered("POST", "/v1/quotes", quote(other.at(0.1, 0.1), other.at(0.2, 0.2)), 201);

        assertThat(source(quote)).isEqualTo("MOCK");
        assertThat(CALLS).hasValue(0);
    }

    @Test
    void etaDispatchReservesTheFasterDriverAndRecordsTheMatrixScore() {
        List<TestDriver> drivers = bookForEta();

        searchOnce();

        UUID offerId = rides.pendingOffer(booked.id());
        assertThat(offeredDriver()).isEqualTo(drivers.get(1).id());
        JsonNode decision = decision();
        assertThat(decision.get("strategy").asString()).isEqualTo("eta");
        JsonNode detail = decision.get("detail");
        assertThat(detail.get("score_unit").asString()).isEqualTo("SECONDS");
        assertThat(detail.has("ranking_fallback")).isFalse();
        assertThat(detail.get("candidates").get(0).get("score").asDouble()).isEqualTo(20.25);
        assertThat(detail.get("candidates").get(0).get("driver_id").asString()).isEqualTo(drivers.get(1).id().toString());
        assertThat(TABLE_CALLS).hasValue(1);
        assertThat(outboxEvents(jdbc, "OfferCreated", offerId)).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("strategy").asString()).isEqualTo("eta");
        });
        assertThat(rides.violations(CITY)).isEmpty();
        assertThat(get(managementPort, "/actuator/prometheus").body()).contains("routing_matrix_requests_total", "dispatch_ranking_total");
    }

    @Test
    void anUnavailableTableKeepsDispatchWorkingWithAnExplicitDistanceFallback() {
        List<TestDriver> drivers = bookForEta();
        TABLE_STATUS.set(503);

        searchOnce();

        assertThat(offeredDriver()).isEqualTo(drivers.getFirst().id());
        JsonNode detail = decision().get("detail");
        assertThat(detail.get("score_unit").asString()).isEqualTo("METRES");
        assertThat(detail.get("ranking_fallback").asString()).isEqualTo("MATRIX_UNAVAILABLE");
        assertThat(rides.violations(CITY)).isEmpty();
    }

    @Test
    void unreachableDriversAreNotOfferedAndTheSearchWidens() {
        bookForEta();
        TABLE_BODY.set("{\"code\":\"Ok\",\"durations\":[[null],[null]]}");

        searchOnce();

        assertThat(rides.pendingOffer(booked.id())).isNull();
        JsonNode decision = decision();
        assertThat(decision.get("outcome").asString()).isEqualTo("NO_CANDIDATES");
        assertThat(decision.get("detail").get("excluded").get("unreachable").asInt()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT radius_m FROM dispatch.search_tasks WHERE ride_id = :id")
                .param("id", booked.id()).query(Integer.class).single()).isEqualTo(3000);
        assertThat(rides.violations(CITY)).isEmpty();
    }

    @Test
    void aDriverGoingOfflineDuringTheTableCallStillLosesTheConditionalReservation() {
        List<TestDriver> drivers = bookForEta();
        TABLE_ACTION.set(() -> TestRides.asApi(() -> dispatch.goOffline(drivers.get(1).id())));

        searchOnce();

        assertThat(offeredDriver()).isEqualTo(drivers.getFirst().id());
        JsonNode tries = decision().get("detail").get("tries");
        assertThat(tries).hasSize(2);
        assertThat(tries.get(0).get("result").asString()).isEqualTo("LOST");
        assertThat(tries.get(1).get("result").asString()).isEqualTo("RESERVED");
        assertThat(rides.violations(CITY)).isEmpty();
    }

    @Test
    void etaAttemptsExcludeDriversAlreadyOfferedTheRideBeforeBuildingTheMatrix() {
        List<TestDriver> drivers = bookForEta();
        searchOnce();
        TestRides.asApi(() -> dispatch.decline(rides.pendingOffer(booked.id()), drivers.get(1).id()));
        TABLE_BODY.set("{\"code\":\"Ok\",\"durations\":[[95.75]]}");
        jdbc.sql("UPDATE dispatch.search_tasks SET due_at = now() WHERE ride_id = :id").param("id", booked.id()).update();

        searchOnce();

        assertThat(offeredDriver()).isEqualTo(drivers.getFirst().id());
        assertThat(decision().get("detail").get("excluded").get("already_offered").asInt()).isEqualTo(1);
        assertThat(TABLE_CALLS).hasValue(2);
    }

        @Test
        void concurrentEtaSearchesStillReserveDifferentDrivers() throws Exception {
        bookForEta();
        var city = new TestCities.TestCity(CITY, 77.3, 12.7);
        TestUser secondRider = rides.rider("Second ETA rider");
        RideView secondRide = rides.book(secondRider.id(), city, PICKUP, "MINI");
        rides.onlyDueIn(CITY);
        TABLE_ACTION.set(() -> await().pollInterval(Duration.ofMillis(5)).atMost(Duration.ofSeconds(1))
            .until(() -> TABLE_CALLS.get() == 2));
        try (var attempts = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = attempts.submit(() -> TestRides.asDispatch(() -> rides.searchTaskPoller().poll()));
            var second = attempts.submit(() -> TestRides.asDispatch(() -> rides.searchTaskPoller().poll()));
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(TABLE_CALLS).hasValue(2);
            assertThat(jdbc.sql("""
                SELECT count(DISTINCT driver_id) FROM dispatch.offers
                WHERE ride_id IN (:rides) AND status = 'PENDING'
                """).param("rides", List.of(booked.id(), secondRide.id())).query(Long.class).single()).isEqualTo(2);
            assertThat(jdbc.sql("""
                SELECT detail->>'score_unit' FROM dispatch.decisions WHERE ride_id IN (:rides)
                """).param("rides", List.of(booked.id(), secondRide.id())).query(String.class).list())
                .containsExactly("SECONDS", "SECONDS");
            assertThat(rides.violations(CITY)).isEmpty();
        } finally {
            assertThat(postJson("/v1/rides/" + secondRide.id() + "/cancel", Map.of("Authorization", secondRider.authorization(),
                Idempotency.HEADER, UUID.randomUUID().toString()), "{}").statusCode()).isEqualTo(200);
        }
        }

        private List<TestDriver> bookForEta() {
        var city = new TestCities.TestCity(CITY, 77.3, 12.7);
        testDrivers.add(rides.onlineAt(city, "MINI", new GeoPoint(PICKUP.lat() + 0.001, PICKUP.lon())));
        testDrivers.add(rides.onlineAt(city, "MINI", new GeoPoint(PICKUP.lat() + 0.005, PICKUP.lon())));
        jdbc.sql("UPDATE geography.city_categories SET ranker = 'eta' WHERE city_id = :city AND category = 'MINI'")
                .param("city", CITY).update();
        bookingRider = rides.rider("ETA rider");
        booked = rides.book(bookingRider.id(), city, PICKUP, "MINI");
        return testDrivers;
    }

    private void searchOnce() {
        rides.onlyDueIn(CITY);
        assertThat(TestRides.asDispatch(() -> rides.searchTaskPoller().poll())).isTrue();
    }

    private UUID offeredDriver() {
        return jdbc.sql("SELECT driver_id FROM dispatch.offers WHERE ride_id = :id AND status = 'PENDING'")
                .param("id", booked.id()).query(UUID.class).single();
    }

    private JsonNode decision() {
        return json.readTree(jdbc.sql("""
                SELECT jsonb_build_object('strategy', strategy, 'outcome', outcome, 'detail', detail)::text
                FROM dispatch.decisions WHERE ride_id = :id ORDER BY attempt DESC LIMIT 1
                """).param("id", booked.id()).query(String.class).single());
    }

    private HttpResponse<String> quote(GeoPoint pickup, GeoPoint dropoff) {
        return postJson("/v1/quotes", Map.of("Authorization", rider),
                json.writeValueAsString(Map.of("pickup", pickup, "dropoff", dropoff, "category", "MINI")));
    }

    private String source(JsonNode quote) {
        return jdbc.sql("SELECT route_source FROM pricing.quotes WHERE id = :id")
                .param("id", UUID.fromString(quote.get("id").asString())).query(String.class).single();
    }

    private String key() {
        return "routing:osrm:v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(BASE_URL.getBytes(StandardCharsets.UTF_8))
                + ":{" + zones.routingCellOf(PICKUP) + "}:" + zones.routingCellOf(DROPOFF);
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/route/v1/driving/", exchange -> {
                try (exchange) {
                    CALLS.incrementAndGet();
                    byte[] bytes = BODY.get().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(STATUS.get(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.createContext("/table/v1/driving/", exchange -> {
                try (exchange) {
                    TABLE_CALLS.incrementAndGet();
                    TABLE_ACTION.getAndSet(() -> { }).run();
                    byte[] bytes = TABLE_BODY.get().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(TABLE_STATUS.get(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}