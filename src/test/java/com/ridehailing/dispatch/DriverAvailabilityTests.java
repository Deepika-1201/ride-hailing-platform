package com.ridehailing.dispatch;

import static com.ridehailing.support.EventContract.outboxEvents;
import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.driver.DriverApi;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.MirrorState;
import com.ridehailing.location.LiveIndex.Status;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.LogContext;
import com.ridehailing.platform.Transactions;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.EventContract;
import com.ridehailing.support.Eventually;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.Postgis;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import tools.jackson.databind.JsonNode;

/** Going online and offline over the API (LLD §8.1, §8.2): rows, sessions, events, audit and the mirror. */
class DriverAvailabilityTests extends IntegrationTest {

    private static final String ME = "/v1/drivers/me";
    private static final String ONLINE = "/v1/drivers/me/online";
    private static final String OFFLINE = "/v1/drivers/me/offline";

    @Autowired
    private TestCities cities;

    @Autowired
    private TestDrivers drivers;

    @Autowired
    private TestUsers users;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private LiveIndex index;

    @Autowired
    private DispatchApi dispatch;

    @Autowired
    private Transactions transactions;

    @Autowired
    private DriverApi driverApi;

    private TestCity city;
    private TestDriver driver;

    @BeforeEach
    void setUp() {
        city = cities.create("MINI", "SEDAN");
        driver = drivers.create(city, "MINI");
    }

    @Test
    void goingOnlineMakesTheDriverAvailableWithASessionAnEventAndAMirror() {
        JsonNode status = assertAnswered("POST", ONLINE, online(driver, driver.vehicleId(), key()), 200);

        assertThat(status.get("status").asString()).isEqualTo("AVAILABLE");
        assertThat(status.get("version").asLong()).isEqualTo(1);
        assertThat(status.get("city_id").asString()).isEqualTo(city.id());
        assertThat(status.get("category").asString()).isEqualTo("MINI");
        assertThat(status.get("vehicle_id").asString()).isEqualTo(driver.vehicleId().toString());
        Instant onlineSince = Instant.parse(status.get("online_since").asString());

        Map<String, Object> session = jdbc.sql("""
                        SELECT city_id, vehicle_id, category, online_at, offline_at FROM dispatch.driver_sessions
                        WHERE driver_id = :driverId
                        """).param("driverId", driver.id()).query().singleRow();
        assertThat(session).containsEntry("city_id", city.id()).containsEntry("vehicle_id", driver.vehicleId())
                .containsEntry("category", "MINI").containsEntry("offline_at", null);
        assertThat(((java.sql.Timestamp) session.get("online_at")).toInstant()).isEqualTo(onlineSince);

        List<JsonNode> events = outboxEvents(jdbc, "DriverWentOnline", driver.id());
        assertThat(events).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("aggregate_type").asString()).isEqualTo("availability");
            assertThat(event.get("aggregate_version").asLong()).isEqualTo(1);
            assertThat(event.get("payload").get("vehicle_id").asString()).isEqualTo(driver.vehicleId().toString());
            assertThat(Instant.parse(event.get("payload").get("online_at").asString())).isEqualTo(onlineSince);
        });
        assertThat(audit(driver.id())).containsExactly("DRIVER " + driver.id() + " availability.online null");
        assertThat(index.mirrored(city.id())).containsEntry(driver.id(),
                new MirrorState(Status.AVAILABLE, 1, "MINI", null));
        JsonNode me = assertAnswered("GET", ME, call("GET", driver.authorization(), ME, null), 200);
        assertThat(me.get("status").get("status").asString()).isEqualTo("AVAILABLE");
        assertThat(me.get("status").get("version").asLong()).isEqualTo(1);
    }

    @Test
    void aRepeatedKeyReplaysAndGoingOnlineAgainWithTheVehicleChangesNothing() {
        String key = key();
        HttpResponse<String> first = online(driver, driver.vehicleId(), key);
        HttpResponse<String> replay = online(driver, driver.vehicleId(), key);
        JsonNode again = assertAnswered("POST", ONLINE, online(driver, driver.vehicleId(), key()), 200);

        assertThat(replay.headers().firstValue(Idempotency.REPLAYED_HEADER)).contains("true");
        assertThat(json(replay)).isEqualTo(json(first));
        assertThat(again).isEqualTo(json(first));
        assertThat(outboxEvents(jdbc, "DriverWentOnline", driver.id())).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM dispatch.driver_sessions WHERE driver_id = :driverId")
                .param("driverId", driver.id()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void anotherVehicleWhileOnlineIsAnInvalidTransition() {
        UUID sedan = drivers.addVehicle(driver.id(), "SEDAN");
        assertAnswered("POST", ONLINE, online(driver, driver.vehicleId(), key()), 200);

        assertProblem("POST", ONLINE, online(driver, sedan, key()), 409, "INVALID_TRANSITION");
        assertThat(dispatch.status(driver.id()).vehicleId()).isEqualTo(driver.vehicleId());
    }

    @Test
    void anUnverifiedDriverIsNotEligibleAndTheKeyCanBeUsedOnceVerified() {
        jdbc.sql("UPDATE driver.drivers SET verification = 'PENDING' WHERE id = :id").param("id", driver.id()).update();
        String key = key();

        assertRefused(online(driver, driver.vehicleId(), key), "Your account isn't verified.");

        jdbc.sql("UPDATE driver.drivers SET verification = 'VERIFIED' WHERE id = :id").param("id", driver.id())
                .update();
        assertAnswered("POST", ONLINE, online(driver, driver.vehicleId(), key), 200);
    }

    @Test
    void aSuspendedDriverIsNotEligible() {
        jdbc.sql("UPDATE driver.drivers SET suspended = true WHERE id = :id").param("id", driver.id()).update();

        assertRefused(online(driver, driver.vehicleId(), key()), "Your account is suspended.");
    }

    @Test
    void onlyTheDriversOwnActiveVehicleInACategoryOfTheCityWillDo() {
        TestDriver other = drivers.create(city, "MINI");
        assertRefused(online(driver, other.vehicleId(), key()), "This vehicle isn't registered to you.");
        assertRefused(online(driver, Ids.newId(), key()), "This vehicle isn't registered to you.");

        UUID xl = drivers.addVehicle(driver.id(), "XL");
        assertRefused(online(driver, xl, key()), "Your city doesn't offer this vehicle's category now.");

        jdbc.sql("UPDATE driver.vehicles SET active = false WHERE id = :id").param("id", driver.vehicleId()).update();
        assertRefused(online(driver, driver.vehicleId(), key()), "This vehicle is inactive.");
    }

    @Test
    void aUserWithoutADriverProfileIsNotEligible() {
        TestUser user = users.create(UserRole.DRIVER);

        assertProblem("POST", ONLINE, postJson(ONLINE, headers(user.authorization(), key()),
                "{\"vehicle_id\": \"" + driver.vehicleId() + "\"}"), 409, "DRIVER_NOT_ELIGIBLE");
    }

    @Test
    void goingOfflineEndsTheSessionWithTheDriversReason() {
        assertAnswered("POST", ONLINE, online(driver, driver.vehicleId(), key()), 200);
        jdbc.sql("""
                        UPDATE dispatch.driver_availability SET online_since = online_since - interval '90 seconds'
                        WHERE driver_id = :driverId
                        """).param("driverId", driver.id()).update();

        JsonNode status = assertAnswered("POST", OFFLINE, offline(driver, key()), 200);

        assertThat(status.get("status").asString()).isEqualTo("OFFLINE");
        assertThat(status.get("version").asLong()).isEqualTo(2);
        assertThat(status.has("category") || status.has("vehicle_id") || status.has("online_since")).isFalse();
        assertThat(jdbc.sql("""
                        SELECT offline_reason FROM dispatch.driver_sessions
                        WHERE driver_id = :driverId AND offline_at >= online_at
                        """).param("driverId", driver.id()).query(String.class).single()).isEqualTo("DRIVER");
        assertThat(outboxEvents(jdbc, "DriverWentOffline", driver.id())).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("aggregate_version").asLong()).isEqualTo(2);
            assertThat(event.get("payload").get("reason").asString()).isEqualTo("DRIVER");
            assertThat(event.get("payload").get("city_id").asString()).isEqualTo(city.id());
            assertThat(event.get("payload").get("online_seconds").asLong()).isBetween(90L, 120L);
        });
        assertThat(audit(driver.id())).containsExactly("DRIVER " + driver.id() + " availability.online null",
                "DRIVER " + driver.id() + " availability.offline DRIVER");
        assertThat(index.mirrored(city.id())).containsEntry(driver.id(), new MirrorState(Status.OFFLINE, 2, null,
                null));
    }

    @Test
    void goingOfflineWhenOfflineChangesNothing() {
        JsonNode never = assertAnswered("POST", OFFLINE, offline(driver, key()), 200);
        assertThat(never.get("status").asString()).isEqualTo("OFFLINE");
        assertThat(never.get("version").asLong()).isZero();

        assertAnswered("POST", ONLINE, online(driver, driver.vehicleId(), key()), 200);
        assertAnswered("POST", OFFLINE, offline(driver, key()), 200);
        JsonNode again = assertAnswered("POST", OFFLINE, offline(driver, key()), 200);

        assertThat(again.get("version").asLong()).isEqualTo(2);
        assertThat(outboxEvents(jdbc, "DriverWentOffline", driver.id())).hasSize(1);
    }

    @Test
    void aDriverOnARideCantGoOffline() {
        assertAnswered("POST", ONLINE, online(driver, driver.vehicleId(), key()), 200);

        for (String status : List.of("ASSIGNED", "ON_TRIP")) {
            jdbc.sql("""
                            UPDATE dispatch.driver_availability SET status = :status, ride_id = :rideId
                            WHERE driver_id = :driverId
                            """)
                    .param("status", status).param("rideId", Ids.newId()).param("driverId", driver.id()).update();
            assertProblem("POST", OFFLINE, offline(driver, key()), 409, "DRIVER_HAS_ACTIVE_RIDE");
        }
        assertThat(outboxEvents(jdbc, "DriverWentOffline", driver.id())).isEmpty();
    }

    @Test
    void aRolledBackChangeIsNeverMirrored() {
        assertThatThrownBy(() -> LogContext.run(Map.of(LogContext.ROLE, "api"), () -> transactions.run(() -> {
            dispatch.goOnline(driver.id(), driver.vehicleId());
            throw new IllegalStateException("rolled back");
        }))).hasMessage("rolled back");

        assertThat(index.mirrored(city.id())).doesNotContainKey(driver.id());
        assertThat(dispatch.status(driver.id()).version()).isZero();
    }

    @Test
    void aDriverWhoMovedCityGoesOnlineInTheNewOne() {
        assertAnswered("POST", ONLINE, online(driver, driver.vehicleId(), key()), 200);
        assertAnswered("POST", OFFLINE, offline(driver, key()), 200);
        TestCity next = cities.create("MINI");
        jdbc.sql("UPDATE driver.drivers SET city_id = :cityId WHERE id = :id").param("cityId", next.id())
                .param("id", driver.id()).update();

        JsonNode status = assertAnswered("POST", ONLINE, online(driver, driver.vehicleId(), key()), 200);

        assertThat(status.get("city_id").asString()).isEqualTo(next.id());
        assertThat(index.mirrored(next.id())).containsKey(driver.id());
    }

    @Test
    void eligibilityIsOnlyAskedInsideATransaction() {
        assertThatThrownBy(() -> driverApi.lockEligibility(driver.id(), driver.vehicleId()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aDriverHasOneOpenSessionAtATimeAndAReasonOnceClosed() {
        assertAnswered("POST", ONLINE, online(driver, driver.vehicleId(), key()), 200);

        assertThatThrownBy(() -> session(driver.id(), null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> session(driver.id(), "now()", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> session(driver.id(), null, "'DRIVER'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyDriversManageTheirAvailability() {
        TestUser rider = users.create(UserRole.RIDER);

        assertProblem("POST", ONLINE, postJson(ONLINE, headers(rider.authorization(), key()),
                "{\"vehicle_id\": \"" + driver.vehicleId() + "\"}"), 403, "FORBIDDEN");
        assertProblem("POST", OFFLINE, postJson(OFFLINE, headers(rider.authorization(), key()), "{}"), 403,
                "FORBIDDEN");
    }

    @Test
    void twoRacingGoOfflineCallsTakeTheDriverOfflineOnce() throws Exception {
        assertAnswered("POST", ONLINE, online(driver, driver.vehicleId(), key()), 200);
        List<Future<DispatchApi.DriverStatusView>> calls = new ArrayList<>();
        try (Connection gate = Postgis.connection(); ExecutorService executor = Executors.newFixedThreadPool(2)) {
            gate.setAutoCommit(false);
            try (var lock = gate.prepareStatement(
                    "SELECT 1 FROM dispatch.driver_availability WHERE driver_id = ? FOR UPDATE")) {
                lock.setObject(1, driver.id());
                lock.executeQuery().close();
            }
            for (int call = 0; call < 2; call++) {
                calls.add(executor.submit(() -> {
                    List<DispatchApi.DriverStatusView> result = new ArrayList<>(1);
                    LogContext.run(Map.of(LogContext.ROLE, "api"), () -> result.add(dispatch.goOffline(driver.id())));
                    return result.getFirst();
                }));
            }
            Eventually.within(Duration.ofSeconds(10), () -> assertThat(sessionsWaitingForALock()).isEqualTo(2));
            gate.rollback();
            for (Future<DispatchApi.DriverStatusView> call : calls) {
                assertThat(call.get(30, TimeUnit.SECONDS).status()).isEqualTo(AvailabilityStatus.OFFLINE);
            }
        }

        assertThat(outboxEvents(jdbc, "DriverWentOffline", driver.id())).hasSize(1);
        assertThat(dispatch.status(driver.id()).version()).isEqualTo(2);
    }

    private long sessionsWaitingForALock() {
        return jdbc.sql("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE wait_event_type = 'Lock' AND datname = current_database()
                        """)
                .query(Long.class)
                .single();
    }

    private void session(UUID driverId, String offlineAt, String reason) {
        jdbc.sql("""
                        INSERT INTO dispatch.driver_sessions (id, driver_id, city_id, vehicle_id, category, online_at,
                                                              offline_at, offline_reason)
                        VALUES (:id, :driverId, 'x', :vehicleId, 'MINI', now(), %s, %s)
                        """.formatted(offlineAt, reason))
                .param("id", Ids.newId())
                .param("driverId", driverId)
                .param("vehicleId", Ids.newId())
                .update();
    }

    private void assertRefused(HttpResponse<String> response, String reason) {
        JsonNode problem = assertProblem("POST", ONLINE, response, 409, "DRIVER_NOT_ELIGIBLE");
        assertThat(problem.get("detail").asString()).isEqualTo(reason);
        assertThat(dispatch.status(driver.id()).status()).isEqualTo(AvailabilityStatus.OFFLINE);
        assertThat(outboxEvents(jdbc, "DriverWentOnline", driver.id())).isEmpty();
    }

    private List<String> audit(UUID driverId) {
        return jdbc.sql("""
                        SELECT actor_type || ' ' || actor_id || ' ' || action || ' ' || coalesce(reason, 'null')
                        FROM audit.audit_log WHERE entity_type = 'availability' AND entity_id = :id ORDER BY occurred_at
                        """)
                .param("id", driverId.toString())
                .query(String.class)
                .list();
    }

    private HttpResponse<String> online(TestDriver who, UUID vehicleId, String key) {
        return postJson(ONLINE, headers(who.authorization(), key), "{\"vehicle_id\": \"" + vehicleId + "\"}");
    }

    private HttpResponse<String> offline(TestDriver who, String key) {
        return postJson(OFFLINE, headers(who.authorization(), key), "{}");
    }

    private static Map<String, String> headers(String authorization, String key) {
        return Map.of("Authorization", authorization, Idempotency.HEADER, key);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
