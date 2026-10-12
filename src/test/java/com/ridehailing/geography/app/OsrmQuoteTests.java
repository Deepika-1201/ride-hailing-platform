package com.ridehailing.geography.app;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.geography.GeographyApi;
import com.ridehailing.geography.app.CityAdministration.NewCity;
import com.ridehailing.geography.db.CategoryRepository.Settings;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.Valkey;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.ValkeyIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
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

@TestPropertySource(properties = {"ride.routing.osrm.enabled=true", "ride.routing.osrm.cache-timeout=100ms"})
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

    private String rider;

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
        rider = users.create(UserRole.RIDER).authorization();
        valkey.await("test-route-clear", WAIT, valkey.commands().del(key()));
        CALLS.set(0);
        STATUS.set(200);
        BODY.set("{\"code\":\"Ok\",\"routes\":[{\"distance\":6656.6,\"duration\":688.3}]}");
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
            server.start();
            return server;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}