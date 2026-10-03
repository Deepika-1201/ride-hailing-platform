package com.ridehailing.geography;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.shared.UserRole;
import com.ridehailing.support.Eventually;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.Postgis;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestUsers;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** LLD §13.3: cities, service and special areas, categories and city settings, through the admin API. */
class AdminGeographyTests extends IntegrationTest {

    private static final String CITIES = "/v1/admin/cities";
    private static final String CITY = "/v1/admin/cities/{city_id}";
    private static final String SERVICE_AREA = "/v1/admin/cities/{city_id}/service-area";
    private static final String SPECIAL_AREAS = "/v1/admin/cities/{city_id}/special-areas";
    private static final String SPECIAL_AREA = "/v1/admin/cities/{city_id}/special-areas/{area_id}";
    private static final String CATEGORIES = "/v1/admin/categories";
    private static final String CITY_CATEGORY = "/v1/admin/cities/{city_id}/categories/{category}";
    private static final String BOW_TIE = "{\"type\": \"Polygon\", \"coordinates\": [[[0, 0], [1, 1], [1, 0], [0, 1], [0, 0]]]}";

    @Autowired
    private TestUsers users;

    @Autowired
    private JdbcClient jdbc;

    private String admin;

    @BeforeEach
    void signIn() {
        admin = users.create(UserRole.ADMIN).authorization();
    }

    @Test
    void aCityIsCreatedReadListedAndUpdatedAtTheVersionTheAdminRead() {
        String id = TestCities.newId();

        JsonNode created = assertAnswered("POST", CITIES,
                call("POST", admin, CITIES, city(id, "Asia/Kolkata", "INR", square(10, 10))), 201);
        assertThat(created.get("version").asInt()).isZero();
        assertThat(created.get("bounds").get("type").asString()).isEqualTo("Polygon");

        assertAnswered("GET", CITY, call("GET", admin, CITIES + "/" + id, null), 200);
        JsonNode listed = assertAnswered("GET", CITIES, call("GET", admin, CITIES, null), 200);
        assertThat(listed.get("items").valueStream().map(city -> city.get("id").asString())).contains(id);

        JsonNode renamed = assertAnswered("PATCH", CITY,
                call("PATCH", admin, CITIES + "/" + id, "{\"name\": \"Renamed\", \"version\": 0}"), 200);
        assertThat(renamed.get("name").asString()).isEqualTo("Renamed");
        assertThat(renamed.get("version").asInt()).isEqualTo(1);

        JsonNode stale = assertProblem("PATCH", CITY,
                call("PATCH", admin, CITIES + "/" + id, "{\"active\": false, \"version\": 0}"), 409, "VERSION_CONFLICT");
        assertThat(stale.get("current_version").asInt()).isEqualTo(1);
        assertThat(json(call("GET", admin, CITIES + "/" + id, null)).get("active").asBoolean()).isTrue();

        assertProblem("PATCH", CITY, call("PATCH", admin, CITIES + "/nowhere", "{\"version\": 0}"), 404, "NOT_FOUND");
        assertProblem("GET", CITY, call("GET", admin, CITIES + "/nowhere", null), 404, "NOT_FOUND");
        assertThat(auditActions("city", id)).containsExactly("city.create", "city.update");
    }

    @Test
    void cityIdsAreUnique() {
        String id = TestCities.newId();
        assertAnswered("POST", CITIES, call("POST", admin, CITIES, city(id, "Asia/Kolkata", "INR", square(11, 11))), 201);

        assertProblem("POST", CITIES, call("POST", admin, CITIES, city(id, "Asia/Kolkata", "INR", square(12, 12))), 409,
                "ALREADY_EXISTS");
    }

    @Test
    void theTimeZoneMustBeAnIanaZoneAndTheCurrencyInr() {
        JsonNode zone = assertProblem("POST", CITIES,
                call("POST", admin, CITIES, city(TestCities.newId(), "+05:30", "INR", square(13, 13))), 400,
                "VALIDATION_FAILED");
        assertThat(zone.get("errors").get(0).get("field").asString()).isEqualTo("time_zone");

        JsonNode currency = assertProblem("POST", CITIES,
                call("POST", admin, CITIES, city(TestCities.newId(), "Asia/Kolkata", "USD", square(13, 13))), 400,
                "VALIDATION_FAILED");
        assertThat(currency.get("errors").get(0).get("field").asString()).isEqualTo("currency");
    }

    @Test
    void shapesThatPostgisFindsInvalidAreRejectedAndNothingIsCreated() {
        String crossing = TestCities.newId();
        assertProblem("POST", CITIES, call("POST", admin, CITIES, city(crossing, "Asia/Kolkata", "INR", BOW_TIE)), 422,
                "INVALID_GEOMETRY");
        String unclosed = TestCities.newId();
        assertProblem("POST", CITIES, call("POST", admin, CITIES, city(unclosed, "Asia/Kolkata", "INR",
                "{\"type\": \"Polygon\", \"coordinates\": [[[0, 0], [1, 0], [1, 1], [0, 1]]]}")), 422, "INVALID_GEOMETRY");

        assertThat(call("GET", admin, CITIES + "/" + crossing, null).statusCode()).isEqualTo(404);
        assertThat(call("GET", admin, CITIES + "/" + unclosed, null).statusCode()).isEqualTo(404);
    }

    @Test
    void aShapeOfTheWrongTypeIsAValidationError() {
        JsonNode problem = assertProblem("POST", CITIES, call("POST", admin, CITIES,
                city(TestCities.newId(), "Asia/Kolkata", "INR", multiSquare(14, 14))), 400, "VALIDATION_FAILED");
        assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("bounds");
    }

    @Test
    void replacingTheServiceAreaLeavesExactlyOneActiveArea() {
        String id = TestCities.newId();
        assertAnswered("POST", CITIES, call("POST", admin, CITIES, city(id, "Asia/Kolkata", "INR", square(15, 15))), 201);

        for (int replacement = 0; replacement < 2; replacement++) {
            assertAnswered("PUT", SERVICE_AREA, call("PUT", admin, serviceArea(id), "{\"area\": " + multiSquare(15, 15)
                    + "}"), 204);
        }

        assertThat(jdbc.sql("SELECT count(*) FILTER (WHERE active) || '/' || count(*) FROM geography.service_areas"
                + " WHERE city_id = :id").param("id", id).query(String.class).single()).isEqualTo("1/2");
        assertProblem("PUT", SERVICE_AREA, call("PUT", admin, serviceArea(id), "{\"area\": {\"type\": \"MultiPolygon\","
                + " \"coordinates\": [[[[0, 0], [1, 1], [1, 0], [0, 1], [0, 0]]]]}}"), 422, "INVALID_GEOMETRY");
        assertProblem("PUT", SERVICE_AREA, call("PUT", admin, serviceArea("nowhere"), "{\"area\": "
                + multiSquare(15, 15) + "}"), 404, "NOT_FOUND");
    }

    @Test
    void concurrentReplacementsOfTheServiceAreaTakeTurns() throws Exception {
        String id = TestCities.newId();
        assertAnswered("POST", CITIES, call("POST", admin, CITIES, city(id, "Asia/Kolkata", "INR", square(18, 18))), 201);
        String area = "{\"area\": " + multiSquare(18, 18) + "}";
        assertThat(call("PUT", admin, serviceArea(id), area).statusCode()).isEqualTo(204);
        List<Future<HttpResponse<String>>> replacements = new ArrayList<>();

        // Blocking writes to the areas from another session lines both replacements up before either commits.
        try (Connection blocker = Postgis.connection(); var executor = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            blocker.createStatement().execute("LOCK TABLE geography.service_areas IN EXCLUSIVE MODE");
            for (int replacement = 0; replacement < 2; replacement++) {
                replacements.add(executor.submit(() -> call("PUT", admin, serviceArea(id), area)));
            }
            Eventually.within(Duration.ofSeconds(10), () -> assertThat(sessionsWaitingForALock()).isEqualTo(2));
            blocker.rollback();
            for (Future<HttpResponse<String>> replacement : replacements) {
                assertThat(replacement.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            }
        }

        assertThat(jdbc.sql("SELECT count(*) FILTER (WHERE active) || '/' || count(*) FROM geography.service_areas"
                + " WHERE city_id = :id").param("id", id).query(String.class).single()).isEqualTo("1/3");
    }

    @Test
    void specialAreasHaveUniqueCodesAndAreDeactivatedNotDeleted() {
        String id = TestCities.newId();
        assertAnswered("POST", CITIES, call("POST", admin, CITIES, city(id, "Asia/Kolkata", "INR", square(16, 16))), 201);
        String code = TestCities.newCode();

        JsonNode area = assertAnswered("POST", SPECIAL_AREAS, call("POST", admin, specialAreas(id),
                specialArea(code)), 201);
        assertThat(area.get("priority").asInt()).isZero();
        assertProblem("POST", SPECIAL_AREAS, call("POST", admin, specialAreas(id), specialArea(code)), 409,
                "ALREADY_EXISTS");
        assertProblem("POST", SPECIAL_AREAS, call("POST", admin, specialAreas("nowhere"),
                specialArea(TestCities.newCode())), 404, "NOT_FOUND");

        String areaPath = specialAreas(id) + "/" + area.get("id").asString();
        assertAnswered("DELETE", SPECIAL_AREA, call("DELETE", admin, areaPath, null), 204);
        JsonNode listed = assertAnswered("GET", SPECIAL_AREAS, call("GET", admin, specialAreas(id), null), 200);
        assertThat(listed.get("items").get(0).get("active").asBoolean()).isFalse();
        assertProblem("DELETE", SPECIAL_AREA, call("DELETE", admin, specialAreas("nowhere") + "/"
                + area.get("id").asString(), null), 404, "NOT_FOUND");
        assertProblem("GET", SPECIAL_AREAS, call("GET", admin, specialAreas("nowhere"), null), 404, "NOT_FOUND");
    }

    @Test
    void cityCategorySettingsAreCreatedByPutAndThenChangedAtTheVersionRead() {
        JsonNode categories = assertAnswered("GET", CATEGORIES, call("GET", admin, CATEGORIES, null), 200);
        assertThat(categories.get("items").valueStream().map(category -> category.get("code").asString()))
                .containsExactly("AUTO", "MINI", "SEDAN", "XL");
        String id = TestCities.newId();
        assertAnswered("POST", CITIES, call("POST", admin, CITIES, city(id, "Asia/Kolkata", "INR", square(17, 17))), 201);
        String path = CITIES + "/" + id + "/categories/MINI";

        assertProblem("GET", CITY_CATEGORY, call("GET", admin, path, null), 404, "NOT_FOUND");
        JsonNode created = assertAnswered("PUT", CITY_CATEGORY, call("PUT", admin, path, settings(15, null)), 200);
        assertThat(created.get("version").asInt()).isZero();
        assertThat(created.get("city_id").asString()).isEqualTo(id);

        JsonNode missing = assertProblem("PUT", CITY_CATEGORY, call("PUT", admin, path, settings(20, null)), 409,
                "VERSION_CONFLICT");
        assertThat(missing.get("current_version").asInt()).isZero();
        JsonNode changed = assertAnswered("PUT", CITY_CATEGORY, call("PUT", admin, path, settings(20, 0)), 200);
        assertThat(changed.get("offer_ttl_s").asInt()).isEqualTo(20);
        assertThat(changed.get("version").asInt()).isEqualTo(1);
        assertProblem("PUT", CITY_CATEGORY, call("PUT", admin, path, settings(25, 0)), 409, "VERSION_CONFLICT");

        assertAnswered("GET", CITY_CATEGORY, call("GET", admin, path, null), 200);
        assertProblem("PUT", CITY_CATEGORY, call("PUT", admin, CITIES + "/" + id + "/categories/BOAT",
                settings(15, null)), 404, "NOT_FOUND");
        assertProblem("PUT", CITY_CATEGORY, call("PUT", admin, path, """
                {"active": true, "offer_ttl_s": 15, "search_timeout_s": 180, "radius_start_m": 7000,
                 "radius_step_m": 1000, "radius_max_m": 6000, "ranker": "nearest", "version": 1}
                """), 400, "VALIDATION_FAILED");
        assertThat(auditActions("city_category", id + ":MINI")).containsExactly("city_category.put",
                "city_category.put");
    }

    @Test
    void onlyAdminsManageReferenceData() {
        String rider = users.create(UserRole.RIDER).authorization();

        assertProblem("GET", CITIES, call("GET", rider, CITIES, null), 403, "FORBIDDEN");
        assertProblem("GET", CITIES, call("GET", null, CITIES, null), 401, "UNAUTHENTICATED");
        assertProblem("GET", CATEGORIES, call("GET", rider, CATEGORIES, null), 403, "FORBIDDEN");
    }

    private long sessionsWaitingForALock() {
        return jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                        + " AND datname = current_database()")
                .query(Long.class)
                .single();
    }

    private List<String> auditActions(String entityType, String entityId) {
        return jdbc.sql("""
                        SELECT action FROM audit.audit_log WHERE entity_type = :type AND entity_id = :id
                        ORDER BY occurred_at, id
                        """)
                .param("type", entityType)
                .param("id", entityId)
                .query(String.class)
                .list();
    }

    private static String city(String id, String timeZone, String currency, String bounds) {
        return """
                {"id": "%s", "name": "Test %s", "time_zone": "%s", "currency": "%s", "bounds": %s}
                """.formatted(id, id, timeZone, currency, bounds);
    }

    private static String square(double lon, double lat) {
        return TestCities.polygon(lon, lat, 0.5).toString();
    }

    private static String multiSquare(double lon, double lat) {
        return TestCities.multiPolygon(lon, lat, 0.5).toString();
    }

    private static String serviceArea(String cityId) {
        return CITIES + "/" + cityId + "/service-area";
    }

    private static String specialAreas(String cityId) {
        return CITIES + "/" + cityId + "/special-areas";
    }

    private static String specialArea(String code) {
        return """
                {"code": "%s", "name": "Stadium", "kind": "STADIUM", "area": %s}
                """.formatted(code, TestCities.multiPolygon(16.1, 16.1, 0.1));
    }

    private static String settings(int offerTtl, Integer version) {
        return """
                {"active": true, "offer_ttl_s": %d, "search_timeout_s": 180, "radius_start_m": 2000,
                 "radius_step_m": 1000, "radius_max_m": 6000, "ranker": "nearest"%s}
                """.formatted(offerTtl, version == null ? "" : ", \"version\": " + version);
    }
}
