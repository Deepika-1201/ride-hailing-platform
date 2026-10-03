package com.ridehailing.pricing;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.platform.Caller;
import com.ridehailing.pricing.app.SurgeRuleAdministration;
import com.ridehailing.pricing.db.RuleRepository.FareRule;
import com.ridehailing.pricing.db.SurgeRuleRepository.SurgeRule;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** LLD §10.4: quotes through the API, against the OpenAPI document. */
class QuoteTests extends IntegrationTest {

    private static final String QUOTES = "/v1/quotes";
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestUsers users;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SurgeRuleAdministration surgeRules;

    private TestCity city;
    private FareRule mini;
    private String rider;

    @BeforeEach
    void setUp() {
        city = cities.create("MINI", "SEDAN");
        mini = prices.price(city.id(), "MINI");
        rider = users.create(UserRole.RIDER).authorization();
    }

    @Test
    void aQuoteFixesTheFareOfTheMockRouteForFiveMinutes() {
        GeoPoint pickup = city.at(0.1, 0.1);
        GeoPoint dropoff = city.at(0.15, 0.13);

        JsonNode quote = assertAnswered("POST", QUOTES, quote(rider, pickup, dropoff, "MINI"), 201);

        int distance = quote.get("distance_m").asInt();
        assertThat(distance).isEqualTo((int) Math.round(pickup.metresTo(dropoff) * 1.35));
        Instant created = Instant.parse(quote.get("created_at").asString());
        assertThat(quote.get("duration_s").asInt()).isIn(mockDuration(distance, created),
                mockDuration(distance, created.minusSeconds(5)));
        assertThat(Duration.between(created, Instant.parse(quote.get("expires_at").asString())))
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(quote.get("surge_multiplier").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(quote.has("pickup_eta_s")).as("no live index before phase 6").isFalse();
        JsonNode fare = quote.get("fare");
        long parts = 0;
        for (String part : new String[] {"base", "distance", "time", "surge", "minimum_topup", "booking_fee", "tax",
            "rounding"}) {
            parts += fare.get(part).get("amount_paise").asLong();
        }
        assertThat(parts).isEqualTo(fare.get("total").get("amount_paise").asLong());
        assertThat(fare.get("base").get("amount_paise").asLong()).isEqualTo(mini.basePaise());

        Map<String, Object> stored = jdbc.sql("""
                        SELECT fare_rule_id, route_source, surge_source, pickup_zone, rider_id FROM pricing.quotes
                        WHERE id = :id
                        """)
                .param("id", UUID.fromString(quote.get("id").asString()))
                .query()
                .singleRow();
        assertThat(stored).containsEntry("fare_rule_id", mini.id()).containsEntry("route_source", "MOCK")
                .containsEntry("surge_source", "NONE").containsEntry("pickup_zone", cellOf(pickup));
    }

    @Test
    void aSurgeRuleInThePickupZoneRaisesTheNextQuote() {
        GeoPoint pickup = city.at(0.2, 0.2);
        GeoPoint dropoff = city.at(0.25, 0.22);
        long before = total(assertAnswered("POST", QUOTES, quote(rider, pickup, dropoff, "MINI"), 201));

        prices.surgeNow(city.id(), cellOf(pickup), "1.30");
        JsonNode surged = assertAnswered("POST", QUOTES, quote(rider, pickup, dropoff, "MINI"), 201);

        assertThat(surged.get("surge_multiplier").decimalValue()).isEqualByComparingTo("1.30");
        assertThat(surged.get("fare").get("surge").get("amount_paise").asLong()).isPositive();
        assertThat(total(surged)).isGreaterThan(before);
        JsonNode elsewhere = assertAnswered("POST", QUOTES, quote(rider, city.at(0.02, 0.02), dropoff, "MINI"), 201);
        assertThat(elsewhere.get("surge_multiplier").decimalValue()).isEqualByComparingTo("1.00");
    }

    @Test
    void aDeactivatedSurgeRuleStopsAtOnce() {
        GeoPoint pickup = city.at(0.3, 0.1);
        SurgeRule rule = prices.surgeNow(city.id(), cellOf(pickup), "1.50");
        assertThat(quoteFrom(pickup).get("surge_multiplier").decimalValue()).isEqualByComparingTo("1.50");

        surgeRules.update(new Caller(Ids.newId(), Set.of(UserRole.ADMIN)), rule.id(), null, false, 0);

        assertThat(quoteFrom(pickup).get("surge_multiplier").decimalValue()).isEqualByComparingTo("1.00");
    }

    @Test
    void aNewlyPublishedFareAppliesToTheNextQuote() {
        GeoPoint pickup = city.at(0.1, 0.3);
        assertAnswered("POST", QUOTES, quote(rider, pickup, city.at(0.2, 0.3), "MINI"), 201);

        FareRule next = prices.fare(city.id(), "MINI", 5_500);

        JsonNode quote = assertAnswered("POST", QUOTES, quote(rider, pickup, city.at(0.2, 0.3), "MINI"), 201);
        assertThat(quote.get("fare").get("base").get("amount_paise").asLong()).isEqualTo(5_500);
        assertThat(fareRuleOf(quote)).isEqualTo(next.id());
    }

    @Test
    void aScheduledFareWaitsForItsTimeAndTheLatestStartedOneApplies() {
        GeoPoint pickup = city.at(0.1, 0.35);
        prices.fareFrom(city.id(), "MINI", 9_900, Instant.now().plus(Duration.ofHours(1)));
        prices.feeFrom(city.id(), "MINI", Instant.now().plus(Duration.ofHours(1)));

        JsonNode quote = assertAnswered("POST", QUOTES, quote(rider, pickup, city.at(0.2, 0.35), "MINI"), 201);
        assertThat(fareRuleOf(quote)).isEqualTo(mini.id());
        assertThat(jdbc.sql("SELECT r.version FROM pricing.quotes q JOIN pricing.fee_rules r ON r.id = q.fee_rule_id"
                + " WHERE q.id = :id").param("id", UUID.fromString(quote.get("id").asString())).query(Integer.class)
                .single()).isEqualTo(1);

        FareRule current = prices.fare(city.id(), "MINI", 4_400);
        JsonNode next = assertAnswered("POST", QUOTES, quote(rider, pickup, city.at(0.2, 0.35), "MINI"), 201);
        assertThat(fareRuleOf(next)).as("version 3, started now, over the scheduled version 2").isEqualTo(current.id());
    }

    @Test
    void theVersionInEffectIsTheOneThatStartedLatestNotTheHighest() {
        UUID startedLatest = insertSedanFare(2, "1 hour");
        insertSedanFare(1, "3 hours");
        insertSedanFare(3, "2 hours");
        jdbc.sql("""
                        INSERT INTO pricing.fee_rules (id, city_id, category, version, effective_from,
                                                       cancellation_fee_paise, no_show_fee_paise, commission_bp, currency,
                                                       created_by)
                        VALUES (gen_random_uuid(), :city, 'SEDAN', 1, now() - interval '3 hours', 5000, 7500, 2000, 'INR',
                                gen_random_uuid())
                        """)
                .param("city", city.id())
                .update();

        JsonNode quote = assertAnswered("POST", QUOTES, quote(rider, city.at(0.1, 0.1), city.at(0.2, 0.2), "SEDAN"),
                201);

        assertThat(fareRuleOf(quote)).isEqualTo(startedLatest);
    }

    @Test
    void theCategoryMustBeOfferedAndPricedInThePickupsCity() {
        GeoPoint pickup = city.at(0.1, 0.1);

        assertProblem("POST", QUOTES, quote(rider, pickup, city.at(0.2, 0.2), "XL"), 422, "CATEGORY_NOT_AVAILABLE");
        assertProblem("POST", QUOTES, quote(rider, pickup, city.at(0.2, 0.2), "SEDAN"), 422,
                "CATEGORY_NOT_AVAILABLE");
        prices.fare(city.id(), "SEDAN", 5_000);
        assertProblem("POST", QUOTES, quote(rider, pickup, city.at(0.2, 0.2), "SEDAN"), 422,
                "CATEGORY_NOT_AVAILABLE");

        String admin = users.create(UserRole.ADMIN).authorization();
        assertThat(call("PUT", admin, "/v1/admin/cities/" + city.id() + "/categories/MINI", """
                {"active": false, "offer_ttl_s": 15, "search_timeout_s": 180, "radius_start_m": 2000,
                 "radius_step_m": 1000, "radius_max_m": 6000, "ranker": "nearest", "version": 0}
                """).statusCode()).isEqualTo(200);
        assertProblem("POST", QUOTES, quote(rider, pickup, city.at(0.2, 0.2), "MINI"), 422, "CATEGORY_NOT_AVAILABLE");
    }

    @Test
    void onlyThePickupMustBeInsideAServiceArea() {
        assertProblem("POST", QUOTES, quote(rider, city.at(-0.05, 0.1), city.at(0.2, 0.2), "MINI"), 422,
                "OUTSIDE_SERVICE_AREA");

        assertAnswered("POST", QUOTES, quote(rider, city.at(0.2, 0.2), city.at(TestCities.SIZE + 0.3, 0.2), "MINI"),
                201);
    }

    @Test
    void aRiderGetsThirtyQuotesAMinute() {
        long started = System.nanoTime();
        int allowed = 0;
        HttpResponse<String> response;
        while ((response = quote(rider, city.at(0.1, 0.1), city.at(0.2, 0.2), "MINI")).statusCode() == 201
                && allowed < 60) {
            allowed++;
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        assertProblem("POST", QUOTES, response, 429, "RATE_LIMITED");
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
        // The bucket refills continuously, one quote every 2 s.
        assertThat(allowed).isBetween(30, 30 + (int) Math.ceil(seconds / 2));
        String other = users.create(UserRole.RIDER).authorization();
        assertAnswered("POST", QUOTES, quote(other, city.at(0.1, 0.1), city.at(0.2, 0.2), "MINI"), 201);
    }

    @Test
    void theDatabaseRejectsAFareWhosePartsDontAddUp() {
        JsonNode quote = assertAnswered("POST", QUOTES, quote(rider, city.at(0.1, 0.1), city.at(0.2, 0.2), "MINI"),
                201);

        assertThatThrownBy(() -> jdbc.sql("UPDATE pricing.quotes SET total_paise = total_paise + 100 WHERE id = :id")
                .param("id", UUID.fromString(quote.get("id").asString())).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requestsAreValidated() {
        assertProblem("POST", QUOTES, call("POST", rider, QUOTES,
                "{\"pickup\": {\"lat\": 12.9, \"lon\": 77.6}, \"category\": \"MINI\"}"), 400, "VALIDATION_FAILED");
        assertProblem("POST", QUOTES, call("POST", rider, QUOTES,
                "{\"pickup\": {\"lat\": 91, \"lon\": 77.6}, \"dropoff\": {\"lat\": 12.9, \"lon\": 77.6},"
                        + " \"category\": \"MINI\"}"), 400, "VALIDATION_FAILED");
        assertProblem("POST", QUOTES, call("POST", rider, QUOTES,
                "{\"pickup\": {\"lat\": 12.9, \"lon\": 77.6}, \"dropoff\": {\"lat\": 12.9, \"lon\": 77.6},"
                        + " \"category\": \"mini\"}"), 400, "VALIDATION_FAILED");
        assertProblem("POST", QUOTES, call("POST", rider, QUOTES,
                "{\"pickup\": {\"lon\": 77.6}, \"dropoff\": {\"lat\": 12.9, \"lon\": 77.6}, \"category\": \"MINI\"}"),
                400, "MALFORMED_REQUEST");
    }

    @Test
    void onlyRidersGetQuotes() {
        String driver = users.create(UserRole.DRIVER).authorization();

        assertProblem("POST", QUOTES, quote(driver, city.at(0.1, 0.1), city.at(0.2, 0.2), "MINI"), 403, "FORBIDDEN");
        assertProblem("POST", QUOTES, quote(null, city.at(0.1, 0.1), city.at(0.2, 0.2), "MINI"), 401,
                "UNAUTHENTICATED");
    }

    private HttpResponse<String> quote(String authorization, GeoPoint pickup, GeoPoint dropoff, String category) {
        return call("POST", authorization, QUOTES, """
                {"pickup": {"lat": %s, "lon": %s}, "dropoff": {"lat": %s, "lon": %s}, "category": "%s"}
                """.formatted(pickup.lat(), pickup.lon(), dropoff.lat(), dropoff.lon(), category));
    }

    private static long total(JsonNode quote) {
        return quote.get("fare").get("total").get("amount_paise").asLong();
    }

    private UUID fareRuleOf(JsonNode quote) {
        return jdbc.sql("SELECT fare_rule_id FROM pricing.quotes WHERE id = :id")
                .param("id", UUID.fromString(quote.get("id").asString())).query(UUID.class).single();
    }

    private JsonNode quoteFrom(GeoPoint pickup) {
        return assertAnswered("POST", QUOTES, quote(rider, pickup, city.at(0.2, 0.2), "MINI"), 201);
    }

    /** Published versions can't move, so past starts go straight into the table, as seeds do. */
    private UUID insertSedanFare(int version, String startedAgo) {
        return jdbc.sql("""
                        INSERT INTO pricing.fare_rules (id, city_id, category, version, effective_from, base_paise,
                                                        per_km_paise, per_min_paise, minimum_paise, booking_fee_paise,
                                                        tax_bp, commission_bp, currency, created_by)
                        VALUES (gen_random_uuid(), :city, 'SEDAN', :version, now() - CAST(:ago AS interval), 5000,
                                1800, 200, 10000, 1000, 500, 2000, 'INR', gen_random_uuid())
                        RETURNING id
                        """)
                .param("city", city.id())
                .param("version", version)
                .param("ago", startedAgo)
                .query(UUID.class)
                .single();
    }

    /** The mock's speeds by local hour (LLD §10.4). */
    private static int mockDuration(int distanceM, Instant departure) {
        int hour = departure.atZone(KOLKATA).getHour();
        int kmh = hour < 6 ? 30 : hour < 8 ? 24 : hour < 11 ? 18 : hour < 17 ? 22 : hour < 21 ? 18 : 26;
        return (int) Math.round(distanceM / (kmh / 3.6));
    }

    private static String cellOf(GeoPoint point) {
        try {
            return H3Core.newInstance().latLngToCellAddress(point.lat(), point.lon(), 7);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
