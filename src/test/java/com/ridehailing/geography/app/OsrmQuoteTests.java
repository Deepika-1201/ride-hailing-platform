package com.ridehailing.geography.app;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static com.ridehailing.support.EventContract.outboxEvents;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.dispatch.db.DriverStatsRepository;
import com.ridehailing.geography.GeographyApi;
import com.ridehailing.geography.RankingWeights;
import com.ridehailing.geography.RoutingProvider;
import com.ridehailing.location.LiveIndex;
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
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private static final String TRAFFIC_CITY = TestCities.newId();
    private static final ZoneId TRAFFIC_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime TRAFFIC_START = LocalTime.now(TRAFFIC_ZONE).minusMinutes(20);
    private static final LocalTime TRAFFIC_END = LocalTime.now(TRAFFIC_ZONE).plusMinutes(20);
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

    @Autowired
    LiveIndex index;

    @Autowired
    DriverStatsRepository driverStats;

    @Autowired
    RoutingProvider routingProvider;

    private static TestCities.TestCity trafficCity;

    private String rider;
    private final List<TestDriver> testDrivers = new ArrayList<>();
    private RideView booked;
    private TestUser bookingRider;

    @DynamicPropertySource
    static void routing(DynamicPropertyRegistry registry) {
        registry.add("ride.routing.osrm.base-url", () -> BASE_URL);
        registry.add("ride.routing.osrm.cities", () -> CITY + "," + TRAFFIC_CITY);
        registry.add("ride.routing.traffic.enabled", () -> true);
        for (int window = 0; window < 2; window++) {
            String prefix = "ride.routing.traffic.cities." + TRAFFIC_CITY + "[" + window + "].";
            String zone = window == 0 ? "*" : "area:PEAK";
            String multiplier = window == 0 ? "1.50" : "2.00";
            registry.add(prefix + "zone-id", () -> zone);
            registry.add(prefix + "multiplier", () -> multiplier);
            registry.add(prefix + "days-of-week", () -> "1,2,3,4,5,6,7");
            registry.add(prefix + "start-local", TRAFFIC_START::toString);
            registry.add(prefix + "end-local", TRAFFIC_END::toString);
        }
    }

    @BeforeEach
    void prepare() {
        if (geography.city(CITY).isEmpty()) {
            cities.create(ADMIN, new NewCity(CITY, "OSRM routing test", "Asia/Kolkata", "INR", TestCities.polygon(77.3, 12.7, 0.4)));
            cities.replaceServiceArea(ADMIN, CITY, TestCities.multiPolygon(77.3, 12.7, 0.4));
            categories.put(ADMIN, new Settings(CITY, "MINI", true, 15, 180, 2000, 1000, 6000, "nearest", 0), null);
        }
        prices.price(CITY, "MINI");
        if (geography.city(TRAFFIC_CITY).isEmpty()) {
            trafficCity = otherCities.createWithId(TRAFFIC_CITY, "MINI");
            otherCities.addSpecialArea(trafficCity, "PEAK", 10, 0.1995, 0.2005, 0.001);
        }
        prices.price(TRAFFIC_CITY, "MINI");
        jdbc.sql("""
            UPDATE geography.city_categories SET ranker = 'nearest', ranking_eta_weight = 1,
                ranking_acceptance_weight = 60, ranking_idle_weight = 2, ranking_heading_weight = 30
            WHERE city_id = :city AND category = 'MINI'
            """)
            .param("city", CITY).update();
        rider = users.create(UserRole.RIDER).authorization();
        valkey.await("test-route-clear", WAIT, valkey.commands().del(key()));
        valkey.await("test-traffic-clear", WAIT, valkey.commands().del(key(trafficCity.at(0.2, 0.2), trafficCity.at(0.3, 0.3))));
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

    @ParameterizedTest
    @ValueSource(strings = {"eta", "weighted"})
    void aDriverGoingOfflineDuringTheTableCallStillLosesTheConditionalReservation(String strategy) {
        List<TestDriver> drivers = bookForEta();
        selectRanker(strategy);
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

    @ParameterizedTest
    @ValueSource(strings = {"eta", "weighted"})
    void concurrentRoadBasedSearchesStillReserveDifferentDrivers(String strategy) throws Exception {
        bookForEta();
        selectRanker(strategy);
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
                .containsExactly(strategy.equals("eta") ? "SECONDS" : "WEIGHTED_SECONDS",
                    strategy.equals("eta") ? "SECONDS" : "WEIGHTED_SECONDS");
            assertThat(rides.violations(CITY)).isEmpty();
        } finally {
            assertThat(postJson("/v1/rides/" + secondRide.id() + "/cancel", Map.of("Authorization", secondRider.authorization(),
                Idempotency.HEADER, UUID.randomUUID().toString()), "{}").statusCode()).isEqualTo(200);
        }
        }

    @Test
    void weightedDispatchUsesAuditedCategoryWeightsAndReproducibleInputs() {
        List<TestDriver> drivers = bookForEta();
        String admin = users.create(UserRole.ADMIN).authorization();
        RankingWeights weights = new RankingWeights(1, 100, 4, 40);
        String body = json.writeValueAsString(Map.of("active", true, "offer_ttl_s", 15, "search_timeout_s", 180,
                "radius_start_m", 2000, "radius_step_m", 1000, "radius_max_m", 6000, "ranker", "weighted",
                "version", categories.get(CITY, "MINI").version(), "ranking_weights", weights));
        assertAnswered("PUT", "/v1/admin/cities/{city_id}/categories/{category}",
                call("PUT", admin, "/v1/admin/cities/" + CITY + "/categories/MINI", body), 200);
        outcomes(drivers.getFirst(), 0, 90, 0);
        outcomes(drivers.get(1), 90, 0, 0);
        jdbc.sql("UPDATE dispatch.driver_availability SET status_changed_at = clock_timestamp() - interval '20 minutes' WHERE driver_id = :id")
                .param("id", drivers.get(1).id()).update();
        motion(drivers.getFirst(), 0, 5);
        motion(drivers.get(1), 180, 5);
        TABLE_BODY.set("{\"code\":\"Ok\",\"durations\":[[100],[100]]}");

        searchOnce();

        assertThat(offeredDriver()).isEqualTo(drivers.get(1).id());
        JsonNode decision = decision();
        assertThat(decision.get("strategy").asString()).isEqualTo("weighted");
        JsonNode detail = decision.get("detail");
        assertThat(detail.get("score_unit").asString()).isEqualTo("WEIGHTED_SECONDS");
        JsonNode candidate = detail.get("candidates").get(0);
        JsonNode score = candidate.get("weighted_score");
        assertThat(score.get("weights")).isEqualTo(json.valueToTree(weights));
        assertThat(score.get("accepted").asLong()).isEqualTo(90);
        assertThat(score.get("acceptance_rate").asDouble()).isCloseTo(0.98, within(1e-12));
        assertThat(score.get("idle_minutes").asDouble()).isBetween(20.0, 20.1);
        assertThat(score.get("heading_penalty").asDouble()).isZero();
        assertThat(score.get("total").asDouble()).isEqualTo(candidate.get("score").asDouble());
        double sum = score.get("eta_cost").asDouble() + score.get("acceptance_cost").asDouble()
                - score.get("idle_credit").asDouble() + score.get("heading_cost").asDouble();
        assertThat(sum).isCloseTo(candidate.get("score").asDouble(), within(1e-10));
        assertThat(detail.get("candidates").get(1).get("weighted_score").get("acceptance_rate").asDouble()).isCloseTo(0.08, within(1e-12));
        assertThat(outboxEvents(jdbc, "OfferCreated", rides.pendingOffer(booked.id()))).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("strategy").asString()).isEqualTo("weighted");
        });
        assertThat(rides.violations(CITY)).isEmpty();
        assertThat(get(managementPort, "/actuator/prometheus").body()).contains("strategy=\"weighted\"");

        TestRides.asApi(() -> dispatch.decline(rides.pendingOffer(booked.id()), offeredDriver()));
        selectRanker("nearest");
        jdbc.sql("UPDATE geography.city_categories SET ranking_eta_weight = 9 WHERE city_id = :city AND category = 'MINI'")
                .param("city", CITY).update();
        TABLE_BODY.set("{\"code\":\"Ok\",\"durations\":[[80]]}");
        try {
            searchOnce();
            JsonNode retried = decision();
            assertThat(retried.get("strategy").asString()).isEqualTo("weighted");
            assertThat(retried.get("detail").get("ranking_assignment")).isEqualTo(detail.get("ranking_assignment"));
            JsonNode retriedScore = retried.get("detail").get("candidates").get(0).get("weighted_score");
            assertThat(retriedScore.get("weights")).isEqualTo(json.valueToTree(weights));
            assertThat(retriedScore.get("eta_cost").asDouble()).isEqualTo(80.0);
        } finally {
            jdbc.sql("UPDATE geography.city_categories SET ranking_eta_weight = :eta WHERE city_id = :city AND category = 'MINI'")
                    .param("eta", weights.eta()).param("city", CITY).update();
        }
    }

    @Test
    void weightedRankingUsesThePriorForMissingStatisticsAndNeverMixesUnitsOnFallback() {
        bookForEta();
        selectRanker("weighted");

        searchOnce();

        JsonNode score = decision().get("detail").get("candidates").get(0).get("weighted_score");
        assertThat(score.get("acceptance_rate").asDouble()).isEqualTo(0.8);
        assertThat(score.get("heading_penalty").asDouble()).isEqualTo(0.5);
        assertThat(score.get("accepted").asLong()).isZero();
        TestRides.asApi(() -> dispatch.decline(rides.pendingOffer(booked.id()), offeredDriver()));
        TABLE_STATUS.set(503);
        jdbc.sql("UPDATE dispatch.search_tasks SET due_at = now() WHERE ride_id = :id").param("id", booked.id()).update();

        searchOnce();

        JsonNode detail = decision().get("detail");
        assertThat(detail.get("score_unit").asString()).isEqualTo("METRES");
        assertThat(detail.get("ranking_fallback").asString()).isEqualTo("MATRIX_UNAVAILABLE");
        assertThat(detail.get("candidates").get(0).has("weighted_score")).isFalse();
    }

    @Test
    void rankingStatisticsAreScopedAndIdleResetsWhenAvailabilityChanges() {
        List<TestDriver> drivers = bookForEta();
        outcomes(drivers.getFirst(), 5, 2, 3);
        jdbc.sql("UPDATE dispatch.driver_availability SET status_changed_at = clock_timestamp() - interval '2 minutes' WHERE driver_id = :id")
                .param("id", drivers.getFirst().id()).update();
        var observed = driverStats.rankingInputs(CITY, drivers.stream().map(TestDriver::id).toList());
        assertThat(observed.get(drivers.getFirst().id()).idleMinutes()).isBetween(2.0, 2.1);
        assertThat(observed.get(drivers.get(1).id()).accepted()).isZero();
        assertThat(driverStats.rankingInputs("other-city", List.of(drivers.getFirst().id()))).isEmpty();
        assertThat(driverStats.rankingInputs(CITY, List.of())).isEmpty();
        assertThatThrownBy(() -> driverStats.rankingInputs(CITY, java.util.Collections.nCopies(11, drivers.getFirst().id())))
            .isInstanceOf(IllegalArgumentException.class);
        TestRides.asApi(() -> dispatch.goOffline(drivers.getFirst().id()));
        assertThat(driverStats.rankingInputs(CITY, List.of(drivers.getFirst().id())).get(drivers.getFirst().id()).idleMinutes()).isZero();
        TestRides.asApi(() -> dispatch.goOnline(drivers.getFirst().id(), drivers.getFirst().vehicleId()));
        assertThat(driverStats.rankingInputs(CITY, List.of(drivers.getFirst().id())).get(drivers.getFirst().id()).idleMinutes()).isBetween(0.0, 0.1);
    }

    @Test
    void trafficChangesQuotedTimeButCachedRoadDataRemainsReusableOutsideTheWindow() {
        GeoPoint pickup = trafficCity.at(0.2, 0.2);
        GeoPoint dropoff = trafficCity.at(0.3, 0.3);
        JsonNode adjusted = assertAnswered("POST", "/v1/quotes", quote(pickup, dropoff), 201);
        assertThat(adjusted.get("duration_s").asInt()).isEqualTo(1032);
        assertThat(adjusted.get("distance_m").asInt()).isEqualTo(6657);
        assertThat(source(adjusted)).isEqualTo("OSRM_ADJUSTED");
        String cached = valkey.await("test-traffic-cache", WAIT, valkey.commands().get(key(pickup, dropoff)));
        assertThat(json.readTree(cached).get("duration_s").asInt()).isEqualTo(688);
        assertThat(json.readTree(cached).get("source").asString()).isEqualTo("OSRM");
        var offPeak = routingProvider.route(pickup, dropoff, ZonedDateTime.now(TRAFFIC_ZONE).plusHours(2)).orElseThrow();
        assertThat(offPeak.durationS()).isEqualTo(688);
        assertThat(offPeak.source()).isEqualTo(RoutingProvider.Route.Source.OSRM);
        assertThat(CALLS).hasValue(1);

        valkey.await("test-traffic-clear", WAIT, valkey.commands().del(key(pickup, dropoff)));
        STATUS.set(503);
        JsonNode fallback = assertAnswered("POST", "/v1/quotes", quote(pickup, dropoff), 201);
        assertThat(source(fallback)).isEqualTo("MOCK_FALLBACK");
        assertThat(valkey.await("test-traffic-empty", WAIT, valkey.commands().get(key(pickup, dropoff)))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"eta", "weighted"})
    void trafficAdjustedRankingRecordsFactorsAgainstTheCorrectOrigins(String strategy) {
        jdbc.sql("""
                UPDATE geography.city_categories SET ranker = :strategy, ranking_eta_weight = 1,
                    ranking_acceptance_weight = 0, ranking_idle_weight = 0, ranking_heading_weight = 0
                WHERE city_id = :city AND category = 'MINI'
                """).param("strategy", strategy).param("city", TRAFFIC_CITY).update();
        TestDriver near = rides.onlineAt(trafficCity, "MINI", trafficCity.at(0.2, 0.201));
        TestDriver far = rides.onlineAt(trafficCity, "MINI", trafficCity.at(0.2, 0.202));
        testDrivers.addAll(List.of(near, far));
        TABLE_BODY.set("{\"code\":\"Ok\",\"durations\":[[20],[25]]}");
        bookingRider = rides.rider("Traffic rider");
        booked = rides.book(bookingRider.id(), trafficCity, trafficCity.at(0.2, 0.2), "MINI");
        rides.onlyDueIn(TRAFFIC_CITY);
        assertThat(TestRides.asDispatch(() -> rides.searchTaskPoller().poll())).isTrue();

        assertThat(offeredDriver()).isEqualTo(far.id());
        JsonNode candidates = decision().get("detail").get("candidates");
        assertThat(candidates.get(0).get("driver_id").asString()).isEqualTo(far.id().toString());
        assertThat(candidates.get(0).get("traffic_multiplier").asDouble()).isEqualTo(1.5);
        assertThat(candidates.get(0).get("score").asDouble()).isEqualTo(37.5);
        assertThat(candidates.get(1).get("driver_id").asString()).isEqualTo(near.id().toString());
        assertThat(candidates.get(1).get("traffic_multiplier").asDouble()).isEqualTo(2);
        assertThat(candidates.get(1).get("score").asDouble()).isEqualTo(40);
    }

    private void outcomes(TestDriver driver, int accepted, int declined, int expired) {
        jdbc.sql("""
                INSERT INTO dispatch.driver_stats (driver_id, offers, accepted, declined, expired, updated_at)
                VALUES (:id, 10000, :accepted, :declined, :expired, now())
                """).param("id", driver.id()).param("accepted", accepted).param("declined", declined).param("expired", expired).update();
    }

    private void motion(TestDriver driver, double heading, double speed) {
        GeoPoint position = index.position(CITY, driver.id()).orElseThrow().position();
        index.update(CITY, driver.id(), "MINI", new LiveIndex.LocationUpdate(2, position, 5, heading, speed, Instant.now()));
    }

    private void selectRanker(String strategy) {
        jdbc.sql("UPDATE geography.city_categories SET ranker = :ranker WHERE city_id = :city AND category = 'MINI'")
                .param("ranker", strategy).param("city", CITY).update();
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
        return key(PICKUP, DROPOFF);
    }

    private String key(GeoPoint from, GeoPoint to) {
        return "routing:osrm:v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(BASE_URL.getBytes(StandardCharsets.UTF_8))
                + ":{" + zones.routingCellOf(from) + "}:" + zones.routingCellOf(to);
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