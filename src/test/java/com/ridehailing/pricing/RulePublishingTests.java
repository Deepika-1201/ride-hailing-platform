package com.ridehailing.pricing;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.shared.UserRole;
import com.ridehailing.support.Eventually;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.Postgis;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestUsers;
import com.uber.h3core.H3Core;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** LLD §10.5: rule versions are consecutive, never edited and never effective in the past; surge rules by zone. */
class RulePublishingTests extends IntegrationTest {

    private static final String FARE_RULES = "/v1/admin/fare-rules";
    private static final String FEE_RULES = "/v1/admin/fee-rules";
    private static final String SURGE_RULES = "/v1/admin/surge-rules";
    private static final String SURGE_RULE = "/v1/admin/surge-rules/{rule_id}";

    @Autowired
    private TestUsers users;

    @Autowired
    private TestCities cities;

    @Autowired
    private JdbcClient jdbc;

    private String admin;
    private TestCity city;

    @BeforeEach
    void setUp() {
        admin = users.create(UserRole.ADMIN).authorization();
        city = cities.create("MINI", "SEDAN");
    }

    @Test
    void eachPublishAddsTheNextVersionAndListsShowTheNewestFirst() {
        JsonNode first = assertAnswered("POST", FARE_RULES, call("POST", admin, FARE_RULES, fare("MINI", null)), 201);
        assertThat(first.get("version").asInt()).isEqualTo(1);
        // Without effective_from, the rule takes effect as it's published, by the database clock.
        assertThat(first.get("effective_from").asString()).isEqualTo(first.get("created_at").asString());
        Instant later = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        JsonNode second = assertAnswered("POST", FARE_RULES, call("POST", admin, FARE_RULES, fare("MINI", later)), 201);
        assertThat(second.get("version").asInt()).isEqualTo(2);
        assertThat(Instant.parse(second.get("effective_from").asString())).isEqualTo(later);
        assertAnswered("POST", FARE_RULES, call("POST", admin, FARE_RULES, fare("SEDAN", null)), 201);

        JsonNode mini = assertAnswered("GET", FARE_RULES,
                call("GET", admin, FARE_RULES + "?city_id=" + city.id() + "&category=MINI", null), 200);
        assertThat(mini.get("items").valueStream().map(rule -> rule.get("version").asInt())).containsExactly(2, 1);
        JsonNode all = assertAnswered("GET", FARE_RULES, call("GET", admin, FARE_RULES + "?city_id=" + city.id(), null),
                200);
        assertThat(all.get("items")).hasSize(3);
    }

    @Test
    void aRuleCantTakeEffectInThePast() {
        Instant past = Instant.now().minus(1, ChronoUnit.MINUTES);

        assertProblem("POST", FARE_RULES, call("POST", admin, FARE_RULES, fare("MINI", past)), 422,
                "RULE_EFFECTIVE_IN_PAST");
        assertProblem("POST", FEE_RULES, call("POST", admin, FEE_RULES, fee("MINI", past)), 422,
                "RULE_EFFECTIVE_IN_PAST");
        assertThat(versions("fare_rules", "MINI")).isEmpty();
    }

    @Test
    void theCategoryMustBeOfferedAndTheCurrencyTheCitys() {
        assertProblem("POST", FARE_RULES, call("POST", admin, FARE_RULES, fare("XL", null)), 422,
                "CATEGORY_NOT_AVAILABLE");
        JsonNode currency = assertProblem("POST", FARE_RULES,
                call("POST", admin, FARE_RULES, fare("MINI", null).replace("\"INR\"", "\"USD\"")), 400,
                "VALIDATION_FAILED");
        assertThat(currency.get("errors").get(0).get("field").asString()).isEqualTo("currency");
        assertProblem("POST", FARE_RULES, call("POST", admin, FARE_RULES,
                fare("MINI", null).replace(city.id(), "nowhere")), 400, "VALIDATION_FAILED");
        assertProblem("GET", FARE_RULES, call("GET", admin, FARE_RULES, null), 400, "VALIDATION_FAILED");
    }

    @Test
    void concurrentPublishesGetConsecutiveVersions() throws Exception {
        int publishers = 5;
        List<Future<HttpResponse<String>>> published = new ArrayList<>();

        // Blocking inserts from another session lines every publisher up inside the database before any commits.
        try (Connection blocker = Postgis.connection(); var executor = Executors.newFixedThreadPool(publishers)) {
            blocker.setAutoCommit(false);
            blocker.createStatement().execute("LOCK TABLE pricing.fare_rules IN EXCLUSIVE MODE");
            for (int publisher = 0; publisher < publishers; publisher++) {
                published.add(executor.submit(() -> call("POST", admin, FARE_RULES, fare("MINI", null))));
            }
            Eventually.within(Duration.ofSeconds(10), () -> assertThat(sessionsWaitingForALock()).isEqualTo(publishers));
            blocker.rollback();
            for (Future<HttpResponse<String>> response : published) {
                assertThat(response.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
            }
        }

        assertThat(versions("fare_rules", "MINI")).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void publishedVersionsAreNeverChanged() {
        JsonNode fare = assertAnswered("POST", FARE_RULES, call("POST", admin, FARE_RULES, fare("MINI", null)), 201);
        JsonNode fee = assertAnswered("POST", FEE_RULES, call("POST", admin, FEE_RULES, fee("MINI", null)), 201);

        assertThatThrownBy(() -> jdbc.sql("UPDATE pricing.fare_rules SET base_paise = 0 WHERE id = :id")
                .param("id", UUID.fromString(fare.get("id").asString())).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("never changed");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM pricing.fee_rules WHERE id = :id")
                .param("id", UUID.fromString(fee.get("id").asString())).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("never changed");
    }

    @Test
    void feeRulesTakeTheDocumentedDefaults() {
        JsonNode fee = assertAnswered("POST", FEE_RULES, call("POST", admin, FEE_RULES, fee("SEDAN", null)), 201);

        assertThat(fee.get("free_cancel_window_s").asInt()).isEqualTo(120);
        assertThat(fee.get("late_grace_s").asInt()).isEqualTo(300);
        assertThat(fee.get("pickup_wait_s").asInt()).isEqualTo(300);
        assertAnswered("GET", FEE_RULES, call("GET", admin, FEE_RULES + "?city_id=" + city.id() + "&category=SEDAN",
                null), 200);
    }

    @Test
    void surgeRulesNeedAZoneOfTheCity() throws Exception {
        H3Core h3 = H3Core.newInstance();
        String cell = h3.latLngToCellAddress(city.lat() + 0.2, city.lon() + 0.2, 7);
        String code = TestCities.newCode();
        cities.addSpecialArea(city, code, 0, 0.1, 0.1, 0.1);

        JsonNode byCell = assertAnswered("POST", SURGE_RULES, call("POST", admin, SURGE_RULES,
                surge(cell, "[1, 2, 3]", "22:00", "02:00")), 201);
        assertThat(byCell.get("days_of_week").valueStream().map(JsonNode::asInt)).containsExactly(1, 2, 3);
        assertThat(byCell.get("start_local").asString()).isEqualTo("22:00");
        assertAnswered("POST", SURGE_RULES, call("POST", admin, SURGE_RULES,
                surge("area:" + code, "[6, 7]", "07:00", "10:00")), 201);

        for (String zone : List.of("area:NOWHERE", h3.latLngToCellAddress(city.lat(), city.lon(), 8), "8f2a")) {
            JsonNode problem = assertProblem("POST", SURGE_RULES,
                    call("POST", admin, SURGE_RULES, surge(zone, "[1]", "07:00", "10:00")), 400, "VALIDATION_FAILED");
            assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("zone_id");
        }
        assertProblem("POST", SURGE_RULES, call("POST", admin, SURGE_RULES, surge(cell, "[1, 1]", "07:00", "10:00")),
                400, "VALIDATION_FAILED");
        assertProblem("POST", SURGE_RULES, call("POST", admin, SURGE_RULES, surge(cell, "[1]", "07:00", "07:00")),
                400, "VALIDATION_FAILED");
        JsonNode listed = assertAnswered("GET", SURGE_RULES, call("GET", admin, SURGE_RULES + "?city_id=" + city.id(),
                null), 200);
        assertThat(listed.get("items")).hasSize(2);
    }

    @Test
    void surgeRulesChangeAtTheVersionTheAdminRead() {
        String cell = cellIn(city);
        JsonNode rule = assertAnswered("POST", SURGE_RULES, call("POST", admin, SURGE_RULES,
                surge(cell, "[1]", "07:00", "10:00")), 201);
        String path = SURGE_RULES + "/" + rule.get("id").asString();

        JsonNode changed = assertAnswered("PATCH", SURGE_RULE, call("PATCH", admin, path,
                "{\"multiplier\": 1.5, \"version\": 0}"), 200);
        assertThat(changed.get("multiplier").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(changed.get("version").asInt()).isEqualTo(1);
        JsonNode stale = assertProblem("PATCH", SURGE_RULE, call("PATCH", admin, path,
                "{\"active\": false, \"version\": 0}"), 409, "VERSION_CONFLICT");
        assertThat(stale.get("current_version").asInt()).isEqualTo(1);
        assertProblem("PATCH", SURGE_RULE, call("PATCH", admin, SURGE_RULES + "/" + UUID.randomUUID(),
                "{\"active\": false, \"version\": 0}"), 404, "NOT_FOUND");
        assertProblem("PATCH", SURGE_RULE, call("PATCH", admin, path, "{\"multiplier\": 2.5, \"version\": 1}"), 400,
                "VALIDATION_FAILED");
    }

    private List<Integer> versions(String table, String category) {
        return jdbc.sql("SELECT version FROM pricing." + table + " WHERE city_id = :cityId AND category = :category"
                        + " ORDER BY version")
                .param("cityId", city.id())
                .param("category", category)
                .query(Integer.class)
                .list();
    }

    private long sessionsWaitingForALock() {
        return jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                        + " AND datname = current_database()")
                .query(Long.class)
                .single();
    }

    private String fare(String category, Instant effectiveFrom) {
        return """
                {"city_id": "%s", "category": "%s",%s "base_paise": 4000, "per_km_paise": 1400, "per_min_paise": 150,
                 "minimum_paise": 8000, "booking_fee_paise": 1000, "tax_bp": 500, "commission_bp": 2000,
                 "currency": "INR"}
                """.formatted(city.id(), category, effectiveFrom == null ? "" : " \"effective_from\": \""
                + effectiveFrom + "\",");
    }

    private String fee(String category, Instant effectiveFrom) {
        return """
                {"city_id": "%s", "category": "%s",%s "cancellation_fee_paise": 5000, "no_show_fee_paise": 7500,
                 "commission_bp": 2000, "currency": "INR"}
                """.formatted(city.id(), category, effectiveFrom == null ? "" : " \"effective_from\": \""
                + effectiveFrom + "\",");
    }

    private String surge(String zone, String days, String start, String end) {
        return """
                {"city_id": "%s", "zone_id": "%s", "days_of_week": %s, "start_local": "%s", "end_local": "%s",
                 "multiplier": 1.2}
                """.formatted(city.id(), zone, days, start, end);
    }

    private static String cellIn(TestCity city) {
        try {
            return H3Core.newInstance().latLngToCellAddress(city.lat() + 0.2, city.lon() + 0.2, 7);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
