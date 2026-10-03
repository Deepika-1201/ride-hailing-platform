package com.ridehailing.pricing;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.geography.RoutingProvider;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestUsers;
import com.uber.h3core.H3Core;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * The phase-5 exit criterion: through the API, a MINI quote for 8.4 km and 26 min at surge 1.2 matches LLD §10.2 to
 * the paisa. A fixed route stands in for the mock, and a fake live index supplies the nearest driver.
 */
@Import(QuoteWorkedExampleTests.Doubles.class)
class QuoteWorkedExampleTests extends IntegrationTest {

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestUsers users;

    @Autowired
    private FakeLiveIndex liveIndex;

    @Autowired
    private FixedRoutes routes;

    @Autowired
    private JdbcClient jdbc;

    private TestCity city;
    private GeoPoint pickup;
    private String rider;

    @BeforeEach
    void setUp() throws IOException {
        city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        pickup = city.at(0.2, 0.2);
        prices.surgeNow(city.id(), H3Core.newInstance().latLngToCellAddress(pickup.lat(), pickup.lon(), 7), "1.20");
        rider = users.create(UserRole.RIDER).authorization();
        liveIndex.candidates.clear();
        liveIndex.calls.clear();
        routes.calls.clear();
    }

    @Test
    void theQuoteMatchesTheWorkedExample() {
        JsonNode quote = assertAnswered("POST", "/v1/quotes", quote(), 201);

        assertThat(quote.get("distance_m").asInt()).isEqualTo(8_400);
        assertThat(quote.get("duration_s").asInt()).isEqualTo(1_560);
        assertThat(quote.get("surge_multiplier").decimalValue()).isEqualByComparingTo("1.20");
        JsonNode fare = quote.get("fare");
        assertThat(Map.of(
                "base", paise(fare, "base"), "distance", paise(fare, "distance"), "time", paise(fare, "time"),
                "surge", paise(fare, "surge"), "minimum_topup", paise(fare, "minimum_topup"),
                "booking_fee", paise(fare, "booking_fee"), "tax", paise(fare, "tax"),
                "rounding", paise(fare, "rounding"), "total", paise(fare, "total")))
                .isEqualTo(Map.of("base", 4_000L, "distance", 11_760L, "time", 3_900L, "surge", 3_932L,
                        "minimum_topup", 0L, "booking_fee", 1_000L, "tax", 1_230L, "rounding", 78L, "total", 25_900L));
        assertThat(jdbc.sql("SELECT commission_paise FROM pricing.quotes WHERE id = :id")
                .param("id", UUID.fromString(quote.get("id").asString())).query(Long.class).single())
                .isEqualTo(4_934L);
    }

    @Test
    void thePickupEtaIsTheNearestDriversRouteToThePickup() {
        GeoPoint driver = city.at(0.21, 0.2);
        liveIndex.candidates.add(new LiveIndex.Candidate(Ids.newId(), driver, 1_100, Instant.now()));

        JsonNode quote = assertAnswered("POST", "/v1/quotes", quote(), 201);

        assertThat(quote.get("pickup_eta_s").asInt()).isEqualTo(FixedRoutes.DRIVER_TO_PICKUP_S);
        assertThat(liveIndex.calls).containsExactly(city.id() + " MINI 6000 1");
        assertThat(routes.calls).contains(new RouteCall(driver, pickup, "Asia/Kolkata"));
    }

    @Test
    void routesDepartAtTheCitysLocalTime() {
        assertAnswered("POST", "/v1/quotes", quote(), 201);

        assertThat(routes.calls).singleElement().satisfies(call -> {
            assertThat(call.from()).isEqualTo(pickup);
            assertThat(call.zone()).isEqualTo("Asia/Kolkata");
        });
    }

    @Test
    void withoutADriverNearbyTheQuoteHasNoEta() {
        JsonNode quote = assertAnswered("POST", "/v1/quotes", quote(), 201);

        assertThat(quote.has("pickup_eta_s")).isFalse();
    }

    private HttpResponse<String> quote() {
        GeoPoint dropoff = city.at(0.3, 0.25);
        return call("POST", rider, "/v1/quotes", """
                {"pickup": {"lat": %s, "lon": %s}, "dropoff": {"lat": %s, "lon": %s}, "category": "MINI"}
                """.formatted(pickup.lat(), pickup.lon(), dropoff.lat(), dropoff.lon()));
    }

    private static long paise(JsonNode fare, String part) {
        return fare.get(part).get("amount_paise").asLong();
    }

    /** Answers {@code nearby} from a list and records the calls; quotes use nothing else. */
    static class FakeLiveIndex implements LiveIndex {

        final List<Candidate> candidates = new CopyOnWriteArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public List<Candidate> nearby(String cityId, String category, GeoPoint at, int radiusM, int k) {
            calls.add(cityId + " " + category + " " + radiusM + " " + k);
            return candidates.stream().limit(k).toList();
        }

        @Override
        public UpdateResult update(String cityId, UUID driverId, String category, LocationUpdate update) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<LivePosition> position(String cityId, UUID driverId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mirror(String cityId, UUID driverId, MirrorState state) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UUID> sweep(String cityId, Instant silentBefore) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<UUID, Instant> lastSeen(String cityId, Collection<UUID> drivers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant epoch(String cityId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<UUID, MirrorState> mirrored(String cityId) {
            throw new UnsupportedOperationException();
        }
    }

    record RouteCall(GeoPoint from, GeoPoint to, String zone) {
    }

    /** The worked example's route for every trip over 5 km; a short drive for anything else. */
    static class FixedRoutes implements RoutingProvider {

        static final int DRIVER_TO_PICKUP_S = 240;

        final List<RouteCall> calls = new CopyOnWriteArrayList<>();

        @Override
        public Optional<Route> route(GeoPoint from, GeoPoint to, ZonedDateTime departure) {
            calls.add(new RouteCall(from, to, departure.getZone().getId()));
            boolean trip = from.metresTo(to) > 5_000;
            return Optional.of(trip ? new Route(8_400, 1_560, Route.Source.MOCK)
                    : new Route(1_100, DRIVER_TO_PICKUP_S, Route.Source.MOCK));
        }
    }

    @TestConfiguration
    static class Doubles {

        @Bean
        @Primary
        FixedRoutes fixedRoutes() {
            return new FixedRoutes();
        }

        @Bean
        @Primary
        FakeLiveIndex liveIndex() {
            return new FakeLiveIndex();
        }
    }
}
