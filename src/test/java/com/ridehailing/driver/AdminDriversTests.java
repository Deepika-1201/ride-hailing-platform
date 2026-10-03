package com.ridehailing.driver;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.AccessTokens;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.EventContract;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** LLD §13.3: onboarding drivers, verifying them and their vehicles; GET /v1/drivers/me. */
class AdminDriversTests extends IntegrationTest {

    private static final String DRIVERS = "/v1/admin/drivers";
    private static final String DRIVER = "/v1/admin/drivers/{driver_id}";
    private static final String VERIFICATION = "/v1/admin/drivers/{driver_id}/verification";
    private static final String VEHICLES = "/v1/admin/vehicles";
    private static final String VEHICLE = "/v1/admin/vehicles/{vehicle_id}";
    private static final String ME = "/v1/drivers/me";

    @Autowired
    private TestUsers users;

    @Autowired
    private TestCities cities;

    @Autowired
    private AccessTokens accessTokens;

    @Autowired
    private JdbcClient jdbc;

    private TestUser admin;
    private TestCity city;

    @BeforeEach
    void setUp() {
        admin = users.create(UserRole.ADMIN);
        city = cities.create("MINI");
    }

    @Test
    void onboardingCreatesAPendingDriverWhoseUserHasTheDriverRole() {
        String phone = TestUsers.randomPhone();

        JsonNode driver = assertAnswered("POST", DRIVERS, call("POST", admin.authorization(), DRIVERS,
                newDriver(phone, city.id())), 201);

        assertThat(driver.get("verification").asString()).isEqualTo("PENDING");
        assertThat(driver.get("vehicles")).isEmpty();
        assertThat(rolesOf(phone)).isEqualTo("DRIVER");
        assertThat(auditActions(driver.get("id").asString())).containsExactly("driver.create");
        assertAnswered("GET", DRIVER, call("GET", admin.authorization(), DRIVERS + "/" + driver.get("id").asString(),
                null), 200);
    }

    @Test
    void aRiderWhoBecomesADriverKeepsTheirAccount() {
        TestUser rider = users.create(UserRole.RIDER);

        JsonNode driver = assertAnswered("POST", DRIVERS, call("POST", admin.authorization(), DRIVERS,
                newDriver(rider.phone(), city.id())), 201);

        assertThat(driver.get("id").asString()).isEqualTo(rider.id().toString());
        assertThat(rolesOf(rider.phone())).isEqualTo("DRIVER,RIDER");
    }

    @Test
    void aPhoneThatIsADriverAlreadyIsAConflictAndTheCityMustExist() {
        String phone = TestUsers.randomPhone();
        assertAnswered("POST", DRIVERS, call("POST", admin.authorization(), DRIVERS, newDriver(phone, city.id())), 201);

        assertProblem("POST", DRIVERS, call("POST", admin.authorization(), DRIVERS, newDriver(phone, city.id())), 409,
                "ALREADY_EXISTS");
        String stranger = TestUsers.randomPhone();
        JsonNode problem = assertProblem("POST", DRIVERS, call("POST", admin.authorization(), DRIVERS,
                newDriver(stranger, "nowhere")), 400, "VALIDATION_FAILED");
        assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("city_id");
        assertThat(rolesOf(stranger)).isNull();
    }

    @Test
    void verifyingRecordsTheChangeOnceAndPublishesDriverVerified() {
        UUID driverId = onboard();
        String path = DRIVERS + "/" + driverId + "/verification";

        JsonNode verified = assertAnswered("POST", VERIFICATION, call("POST", admin.authorization(), path,
                verification("VERIFIED")), 200);
        assertThat(verified.get("verification").asString()).isEqualTo("VERIFIED");
        assertAnswered("POST", VERIFICATION, call("POST", admin.authorization(), path, verification("VERIFIED")), 200);

        assertThat(statusChanges(driverId)).containsExactly("PENDING>VERIFIED");
        List<JsonNode> events = EventContract.outboxEvents(jdbc, "DriverVerified", driverId);
        assertThat(events).hasSize(1);
        EventContract.assertConforms(events.getFirst());
        JsonNode payload = events.getFirst().get("payload");
        assertThat(payload.get("city_id").asString()).isEqualTo(city.id());
        assertThat(payload.get("verified_by").asString()).isEqualTo(admin.id().toString());
        assertThat(events.getFirst().get("producer").asString()).isEqualTo("driver/api");

        assertAnswered("POST", VERIFICATION, call("POST", admin.authorization(), path, verification("REJECTED")), 200);
        assertThat(statusChanges(driverId)).containsExactly("PENDING>VERIFIED", "VERIFIED>REJECTED");
        assertThat(EventContract.outboxEvents(jdbc, "DriverVerified", driverId)).hasSize(1);
        assertThat(auditActions(driverId.toString())).containsExactly("driver.create", "driver.verification",
                "driver.verification");
    }

    @Test
    void verificationTakesOnlyAnOutcomeAndAKnownDriver() {
        UUID driverId = onboard();

        assertProblem("POST", VERIFICATION, call("POST", admin.authorization(), DRIVERS + "/" + driverId
                + "/verification", verification("PENDING")), 400, "VALIDATION_FAILED");
        assertProblem("POST", VERIFICATION, call("POST", admin.authorization(), DRIVERS + "/" + UUID.randomUUID()
                + "/verification", verification("VERIFIED")), 404, "NOT_FOUND");
        assertProblem("GET", DRIVER, call("GET", admin.authorization(), DRIVERS + "/" + UUID.randomUUID(), null), 404,
                "NOT_FOUND");
    }

    @Test
    void theDriverListPagesNewestFirstAndFilters() {
        TestCity own = cities.create("MINI");
        List<UUID> onboarded = List.of(onboard(own), onboard(own), onboard(own));
        call("POST", admin.authorization(), DRIVERS + "/" + onboarded.getFirst() + "/verification",
                verification("VERIFIED"));
        String query = DRIVERS + "?city_id=" + own.id() + "&limit=2";

        JsonNode first = assertAnswered("GET", DRIVERS, call("GET", admin.authorization(), query, null), 200);
        JsonNode second = assertAnswered("GET", DRIVERS, call("GET", admin.authorization(),
                query + "&cursor=" + first.get("next_cursor").asString(), null), 200);

        assertThat(second.has("next_cursor")).isFalse();
        assertThat(ids(first)).containsExactly(onboarded.get(2), onboarded.get(1));
        assertThat(ids(second)).containsExactly(onboarded.get(0));
        JsonNode exactlyFull = assertAnswered("GET", DRIVERS, call("GET", admin.authorization(),
                DRIVERS + "?city_id=" + own.id() + "&limit=3", null), 200);
        assertThat(exactlyFull.get("items")).hasSize(3);
        assertThat(exactlyFull.has("next_cursor")).isFalse();
        JsonNode verified = assertAnswered("GET", DRIVERS, call("GET", admin.authorization(),
                DRIVERS + "?city_id=" + own.id() + "&verification=VERIFIED", null), 200);
        assertThat(ids(verified)).containsExactly(onboarded.getFirst());
        assertProblem("GET", DRIVERS, call("GET", admin.authorization(), DRIVERS + "?cursor=bm90LWEtY3Vyc29y", null),
                400, "VALIDATION_FAILED");
        assertProblem("GET", DRIVERS, call("GET", admin.authorization(), DRIVERS + "?limit=0", null), 400,
                "VALIDATION_FAILED");
        assertProblem("GET", DRIVERS, call("GET", admin.authorization(), DRIVERS + "?limit=101", null), 400,
                "VALIDATION_FAILED");
    }

    @Test
    void vehiclesNeedAnOfferedCategoryAUniquePlateAndTheVersionRead() {
        UUID driverId = onboard();
        String plate = plate();

        JsonNode vehicle = assertAnswered("POST", VEHICLES, call("POST", admin.authorization(), VEHICLES,
                newVehicle(driverId, "MINI", plate)), 201);
        assertProblem("POST", VEHICLES, call("POST", admin.authorization(), VEHICLES, newVehicle(driverId, "XL",
                plate())), 422, "CATEGORY_NOT_AVAILABLE");
        assertProblem("POST", VEHICLES, call("POST", admin.authorization(), VEHICLES, newVehicle(driverId, "MINI",
                plate)), 409, "ALREADY_EXISTS");
        assertProblem("POST", VEHICLES, call("POST", admin.authorization(), VEHICLES, newVehicle(UUID.randomUUID(),
                "MINI", plate())), 404, "NOT_FOUND");

        String path = VEHICLES + "/" + vehicle.get("id").asString();
        JsonNode parked = assertAnswered("PATCH", VEHICLE, call("PATCH", admin.authorization(), path,
                "{\"active\": false, \"version\": 0}"), 200);
        assertThat(parked.get("version").asInt()).isEqualTo(1);
        JsonNode stale = assertProblem("PATCH", VEHICLE, call("PATCH", admin.authorization(), path,
                "{\"active\": true, \"version\": 0}"), 409, "VERSION_CONFLICT");
        assertThat(stale.get("current_version").asInt()).isEqualTo(1);
        assertProblem("PATCH", VEHICLE, call("PATCH", admin.authorization(), VEHICLES + "/" + UUID.randomUUID(),
                "{\"active\": true, \"version\": 0}"), 404, "NOT_FOUND");
        JsonNode driver = json(call("GET", admin.authorization(), DRIVERS + "/" + driverId, null));
        assertThat(driver.get("vehicles").get(0).get("active").asBoolean()).isFalse();
    }

    @Test
    void aDriverSeesTheirProfileAndAnOfflineStatus() {
        UUID driverId = onboard();
        call("POST", admin.authorization(), VEHICLES, newVehicle(driverId, "MINI", plate()));
        String driver = "Bearer " + accessTokens.issue(driverId, Set.of(UserRole.DRIVER)).value();

        JsonNode me = assertAnswered("GET", ME, call("GET", driver, ME, null), 200);

        assertThat(me.get("id").asString()).isEqualTo(driverId.toString());
        assertThat(me.get("vehicles")).hasSize(1);
        assertThat(me.get("status").get("status").asString()).isEqualTo("OFFLINE");
        assertThat(me.get("status").get("version").asInt()).isZero();
        assertProblem("GET", ME, call("GET", users.create(UserRole.DRIVER).authorization(), ME, null), 404,
                "NOT_FOUND");
        assertProblem("GET", ME, call("GET", users.create(UserRole.RIDER).authorization(), ME, null), 403, "FORBIDDEN");
        assertProblem("GET", DRIVERS, call("GET", driver, DRIVERS, null), 403, "FORBIDDEN");
    }

    private UUID onboard() {
        return onboard(city);
    }

    private UUID onboard(TestCity in) {
        JsonNode driver = json(call("POST", admin.authorization(), DRIVERS, newDriver(TestUsers.randomPhone(),
                in.id())));
        return UUID.fromString(driver.get("id").asString());
    }

    private String rolesOf(String phone) {
        return jdbc.sql("SELECT array_to_string(roles, ',') FROM identity.users WHERE phone = :phone")
                .param("phone", phone)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private List<String> statusChanges(UUID driverId) {
        return jdbc.sql("""
                        SELECT from_value || '>' || to_value FROM driver.status_changes
                        WHERE driver_id = :driverId AND kind = 'VERIFICATION' ORDER BY occurred_at, id
                        """)
                .param("driverId", driverId)
                .query(String.class)
                .list();
    }

    private List<String> auditActions(String driverId) {
        return jdbc.sql("""
                        SELECT action FROM audit.audit_log WHERE entity_type = 'driver' AND entity_id = :id
                        ORDER BY occurred_at, id
                        """)
                .param("id", driverId)
                .query(String.class)
                .list();
    }

    private static List<UUID> ids(JsonNode page) {
        return page.get("items").valueStream().map(driver -> UUID.fromString(driver.get("id").asString())).toList();
    }

    private static String newDriver(String phone, String cityId) {
        return """
                {"phone": "%s", "first_name": "Ravi", "last_name": "Kumar", "city_id": "%s"}
                """.formatted(phone, cityId);
    }

    private static String verification(String status) {
        return "{\"status\": \"%s\", \"reason\": \"Documents checked\"}".formatted(status);
    }

    private static String newVehicle(UUID driverId, String category, String plate) {
        return """
                {"driver_id": "%s", "category": "%s", "plate": "%s", "make": "Maruti Suzuki", "model": "Wagon R",
                 "colour": "White"}
                """.formatted(driverId, category, plate);
    }

    private static String plate() {
        return "KA 01 T " + ThreadLocalRandom.current().nextInt(1_000_000);
    }
}
