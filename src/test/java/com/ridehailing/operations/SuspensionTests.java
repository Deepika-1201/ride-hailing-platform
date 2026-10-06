package com.ridehailing.operations;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static com.ridehailing.support.TestRides.asApi;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.Poller;
import com.ridehailing.ride.RideView;
import com.ridehailing.ride.app.DriverCommands;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.EventContract;
import com.ridehailing.support.Eventually;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.Postgis;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestRides.AssignedRide;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** FR-D5, LLD §8.8: suspension blocks offers at once, lets a ride finish, and lifts cleanly. */
class SuspensionTests extends IntegrationTest {

    static final String SUSPEND = "/v1/ops/drivers/{driver_id}/suspend";
    static final String REINSTATE = "/v1/ops/drivers/{driver_id}/reinstate";

    @Autowired
    TestCities cities;

    @Autowired
    TestPrices prices;

    @Autowired
    TestRides rides;

    @Autowired
    TestUsers users;

    @Autowired
    DriverCommands driverCommands;

    @Autowired
    JdbcClient jdbc;

    private TestUser ops;

    @BeforeEach
    void operations() {
        ops = users.create(UserRole.OPS);
    }

    @Test
    void suspendingAnAvailableDriverTakesThemOfflineAndKeepsThemOff() {
        TestCity city = city();
        TestDriver driver = rides.onlineAt(city, "MINI", pickup(city));

        JsonNode suspended = assertAnswered("POST", SUSPEND, suspend(ops, driver.id(), "Repeated complaints"), 200);

        assertThat(suspended.get("suspended").asBoolean()).isTrue();
        assertThat(suspended.get("suspension_reason").asString()).isEqualTo("Repeated complaints");
        assertThat(suspended.get("status").get("status").asString()).isEqualTo("OFFLINE");
        assertThat(suspended.get("rating").toString()).isEqualTo("{\"count\":0}");
        assertThat(sessionReason(driver)).isEqualTo("SUSPENDED");
        JsonNode event = EventContract.outboxEvents(jdbc, "DriverSuspended", driver.id()).getFirst();
        EventContract.assertConforms(event);
        assertThat(event.get("payload").get("reason").asString()).isEqualTo("Repeated complaints");
        assertThat(event.get("payload").get("by").asString()).isEqualTo(ops.id().toString());
        assertThat(EventContract.outboxEvents(jdbc, "DriverWentOffline", driver.id()).getLast().get("payload")
                .get("reason").asString()).isEqualTo("SUSPENDED");
        assertThat(statusChanges(driver)).containsExactly("SUSPENSION ACTIVE→SUSPENDED: Repeated complaints");
        assertThat(audited(driver)).containsExactly("driver.suspend by OPS: Repeated complaints");
        assertProblem("POST", "/v1/drivers/me/online", goOnline(driver), 409, "DRIVER_NOT_ELIGIBLE");
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void suspensionWithdrawsAPendingOfferInTheSameTransaction() {
        TestCity city = city();
        TestDriver driver = rides.onlineAt(city, "MINI", pickup(city));
        RideView ride = rides.book(rides.rider("Rider").id(), city, pickup(city), "MINI");
        rides.onlyDueIn(city.id());
        rides.search();
        UUID offer = rides.pendingOffer(ride.id());
        assertThat(offer).isNotNull();
        TestDriver other = rides.onlineAt(city, "MINI", city.at(0.11, 0.1));

        assertAnswered("POST", SUSPEND, suspend(ops, driver.id(), "Fraud"), 200);

        assertThat(rides.offerStatus(offer)).isEqualTo("WITHDRAWN");
        JsonNode withdrawn = EventContract.outboxEvents(jdbc, "OfferWithdrawn", offer).getFirst();
        EventContract.assertConforms(withdrawn);
        assertThat(withdrawn.get("payload").get("reason").asString()).isEqualTo("SUSPENDED");
        assertThat(rides.timers().exists("OFFER_EXPIRY", offer)).as("the offer's timer").isFalse();
        assertThat(jdbc.sql("SELECT due_at <= now() FROM dispatch.search_tasks WHERE ride_id = :id")
                .param("id", ride.id()).query(Boolean.class).single()).as("the ride searches on at once").isTrue();
        assertThat(availability(driver)).isEqualTo("OFFLINE");
        assertThat(jdbc.sql("""
                        SELECT count(DISTINCT xmin::text) FROM (
                            SELECT xmin FROM driver.drivers WHERE id = :driver
                            UNION ALL SELECT xmin FROM dispatch.driver_availability WHERE driver_id = :driver
                            UNION ALL SELECT xmin FROM dispatch.offers WHERE id = :offer
                            UNION ALL SELECT xmin FROM platform.outbox
                                      WHERE event_type IN ('DriverSuspended', 'DriverWentOffline', 'OfferWithdrawn')
                                        AND aggregate_id IN (:driver, :offer)) changed
                        """).param("driver", driver.id()).param("offer", offer).query(Long.class).single())
                .as("written by one transaction").isEqualTo(1);
        assertThat(rides.violations(city.id())).isEmpty();

        rides.search();

        assertThat(jdbc.sql("SELECT driver_id FROM dispatch.offers WHERE ride_id = :id AND status = 'PENDING'")
                .param("id", ride.id()).query(UUID.class).single()).isEqualTo(other.id());
    }

    @Test
    void aDriverSuspendedDuringARideFinishesItAndThenGoesOffline() {
        TestCity city = city();
        AssignedRide ride = rides.assigned(city, pickup(city), pickup(city));

        JsonNode suspended = assertAnswered("POST", SUSPEND, suspend(ops, ride.driver().id(), "Unsafe driving"), 200);

        assertThat(suspended.get("status").get("status").asString()).isEqualTo("ASSIGNED");
        assertThat(offlineAfterRide(ride.driver())).isTrue();
        assertThat(rides.violations(city.id())).isEmpty();
        finish(ride);
        assertThat(rides.rideStatus(ride.id())).isEqualTo("COMPLETED");
        assertThat(availability(ride.driver())).isEqualTo("OFFLINE");
        assertThat(sessionReason(ride.driver())).isEqualTo("SUSPENDED");
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void aDriverReinstatedDuringTheRideTheyWereSuspendedInStaysOnlineAfterIt() {
        TestCity city = city();
        AssignedRide ride = rides.assigned(city, pickup(city), pickup(city));
        assertAnswered("POST", SUSPEND, suspend(ops, ride.driver().id(), "Complaint"), 200);

        assertAnswered("POST", REINSTATE, reinstate(ops, ride.driver().id(), "Complaint withdrawn"), 200);

        assertThat(offlineAfterRide(ride.driver())).isFalse();
        finish(ride);
        assertThat(availability(ride.driver())).isEqualTo("AVAILABLE");
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void reinstatementLiftsTheSuspensionAndTheDriverMayGoOnlineAgain() {
        TestCity city = city();
        TestDriver driver = rides.onlineAt(city, "MINI", pickup(city));
        assertAnswered("POST", SUSPEND, suspend(ops, driver.id(), "Documents expired"), 200);

        JsonNode reinstated = assertAnswered("POST", REINSTATE, reinstate(ops, driver.id(), "Documents renewed"), 200);

        assertThat(reinstated.get("suspended").asBoolean()).isFalse();
        assertThat(reinstated.has("suspension_reason")).isFalse();
        JsonNode event = EventContract.outboxEvents(jdbc, "DriverReinstated", driver.id()).getFirst();
        EventContract.assertConforms(event);
        assertThat(event.get("payload").get("reason").asString()).isEqualTo("Documents renewed");
        assertThat(statusChanges(driver)).containsExactly("SUSPENSION ACTIVE→SUSPENDED: Documents expired",
                "REINSTATEMENT SUSPENDED→ACTIVE: Documents renewed");
        assertThat(audited(driver)).containsExactly("driver.suspend by OPS: Documents expired",
                "driver.reinstate by OPS: Documents renewed");
        assertThat(goOnline(driver).statusCode()).isEqualTo(200);
        assertThat(availability(driver)).isEqualTo("AVAILABLE");
    }

    @Test
    void suspendingASuspendedDriverOrReinstatingAnActiveOneChangesNothing() {
        TestCity city = city();
        TestDriver driver = rides.onlineAt(city, "MINI", pickup(city));
        TestDriver never = rides.onlineAt(city, "MINI", pickup(city));
        assertAnswered("POST", SUSPEND, suspend(ops, driver.id(), "First reason"), 200);

        JsonNode again = assertAnswered("POST", SUSPEND, suspend(ops, driver.id(), "Second reason"), 200);
        assertAnswered("POST", REINSTATE, reinstate(ops, driver.id(), "Lifted"), 200);
        assertAnswered("POST", REINSTATE, reinstate(ops, driver.id(), "Lifted again"), 200);
        JsonNode untouched = assertAnswered("POST", REINSTATE, reinstate(ops, never.id(), "Nothing to lift"), 200);

        assertThat(again.get("suspension_reason").asString()).isEqualTo("First reason");
        assertThat(EventContract.outboxEvents(jdbc, "DriverSuspended", driver.id())).hasSize(1);
        assertThat(EventContract.outboxEvents(jdbc, "DriverReinstated", driver.id())).hasSize(1);
        assertThat(statusChanges(driver)).hasSize(2);
        assertThat(untouched.get("suspended").asBoolean()).isFalse();
        assertThat(untouched.get("status").get("status").asString()).isEqualTo("AVAILABLE");
        assertThat(EventContract.outboxEvents(jdbc, "DriverReinstated", never.id())).isEmpty();
        assertThat(statusChanges(never)).isEmpty();
    }

    @Test
    void onlyOperationsAndAdminsSuspendAKnownDriverWithAReason() {
        TestCity city = city();
        TestDriver driver = rides.onlineAt(city, "MINI", pickup(city));
        TestUser rider = users.create(UserRole.RIDER);
        String key = UUID.randomUUID().toString();

        assertProblem("POST", SUSPEND, suspend(ops, UUID.randomUUID(), "Unknown"), 404, "NOT_FOUND");
        assertProblem("POST", SUSPEND, suspend(ops, rider.id(), "Not a driver"), 404, "NOT_FOUND");
        assertProblem("POST", SUSPEND, post(ops, "/v1/ops/drivers/" + driver.id() + "/suspend", "{\"reason\": \" \"}",
                UUID.randomUUID().toString()), 400, "VALIDATION_FAILED");
        assertProblem("POST", SUSPEND, post(ops, "/v1/ops/drivers/" + driver.id() + "/suspend",
                "{\"reason\": \"No key\"}", null), 400, "IDEMPOTENCY_KEY_REQUIRED");
        assertProblem("POST", SUSPEND, suspend(rider, driver.id(), "Riders can't"), 403, "FORBIDDEN");
        assertProblem("POST", SUSPEND, suspend(new TestUser(driver.id(), null, null, driver.authorization()),
                driver.id(), "Drivers can't"), 403, "FORBIDDEN");
        assertThat(availability(driver)).isEqualTo("AVAILABLE");

        JsonNode first = assertAnswered("POST", SUSPEND, post(users.create(UserRole.ADMIN),
                "/v1/ops/drivers/" + driver.id() + "/suspend", "{\"reason\": \"By an admin\"}", key), 200);
        assertThat(first.get("suspended").asBoolean()).isTrue();
        assertThat(audited(driver)).containsExactly("driver.suspend by ADMIN: By an admin");
    }

    /**
     * An offer lands between the suspension's lockless read and its lock: the gate holds the driver's availability
     * while the search attempt, then the suspension, queue for it. Operations get {@code 409}, nothing changed, and
     * the retry withdraws the offer.
     */
    @Test
    void anOfferArrivingAsTheDriverIsSuspendedMakesOperationsTryAgain() throws Exception {
        TestCity city = city();
        TestDriver driver = rides.onlineAt(city, "MINI", pickup(city));
        RideView ride = rides.book(rides.rider("Rider").id(), city, pickup(city), "MINI");
        rides.onlyDueIn(city.id());
        Poller poller = rides.searchTaskPoller();
        try (Connection gate = Postgis.connection(); ExecutorService threads = Executors.newFixedThreadPool(2)) {
            gate.setAutoCommit(false);
            try (var lock = gate.prepareStatement(
                    "SELECT 1 FROM dispatch.driver_availability WHERE driver_id = ? FOR UPDATE")) {
                lock.setObject(1, driver.id());
                lock.executeQuery().close();
            }
            Future<Boolean> attempt = threads.submit(() -> TestRides.asDispatch(poller::poll));
            Eventually.within(Duration.ofSeconds(10), () -> assertThat(sessionsWaitingForALock()).isEqualTo(1));
            Future<HttpResponse<String>> suspension = threads.submit(() -> suspend(ops, driver.id(), "Fraud"));
            Eventually.within(Duration.ofSeconds(10), () -> assertThat(sessionsWaitingForALock()).isEqualTo(2));
            gate.rollback();

            assertThat(attempt.get(30, TimeUnit.SECONDS)).isTrue();
            assertProblem("POST", SUSPEND, suspension.get(30, TimeUnit.SECONDS), 409, "INVALID_TRANSITION");
        }
        UUID offer = rides.pendingOffer(ride.id());
        assertThat(offer).as("the offer stands").isNotNull();
        assertThat(suspended(driver)).as("nothing changed").isFalse();
        assertThat(EventContract.outboxEvents(jdbc, "DriverSuspended", driver.id())).isEmpty();
        assertThat(rides.violations(city.id())).isEmpty();

        assertAnswered("POST", SUSPEND, suspend(ops, driver.id(), "Fraud"), 200);

        assertThat(rides.offerStatus(offer)).isEqualTo("WITHDRAWN");
        assertThat(availability(driver)).isEqualTo("OFFLINE");
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void invariantI8ReportsASuspendedDriverWhoCouldBeMatched() {
        TestCity city = city();
        TestDriver available = rides.onlineAt(city, "MINI", pickup(city));
        TestDriver offered = rides.onlineAt(city, "MINI", city.at(0.3, 0.3));
        RideView ride = rides.book(rides.rider("Rider").id(), city, city.at(0.3, 0.3), "MINI");
        rides.onlyDueIn(city.id());
        rides.search();
        assertThat(rides.pendingOffer(ride.id())).isNotNull();

        jdbc.sql("UPDATE driver.drivers SET suspended = true, suspension_reason = 'Broken on purpose' WHERE id IN (:ids)")
                .param("ids", List.of(available.id(), offered.id())).update();

        assertThat(rides.violations(city.id())).containsExactlyInAnyOrder("I8: driver " + available.id()
                + " is suspended but AVAILABLE", "I8: driver " + offered.id() + " is suspended but OFFERED");
        jdbc.sql("UPDATE driver.drivers SET suspended = false, suspension_reason = NULL WHERE id IN (:ids)")
                .param("ids", List.of(available.id(), offered.id())).update();
    }

    private TestCity city() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        return city;
    }

    private static GeoPoint pickup(TestCity city) {
        return city.at(0.1, 0.1);
    }

    private void finish(AssignedRide ride) {
        asApi(() -> {
            driverCommands.arrive(ride.driver().id(), ride.id());
            driverCommands.start(ride.driver().id(), ride.id(), ride.pin());
            return driverCommands.complete(ride.driver().id(), ride.id());
        });
    }

    HttpResponse<String> suspend(TestUser by, UUID driverId, String reason) {
        return post(by, "/v1/ops/drivers/" + driverId + "/suspend", "{\"reason\": \"" + reason + "\"}",
                UUID.randomUUID().toString());
    }

    HttpResponse<String> reinstate(TestUser by, UUID driverId, String reason) {
        return post(by, "/v1/ops/drivers/" + driverId + "/reinstate", "{\"reason\": \"" + reason + "\"}",
                UUID.randomUUID().toString());
    }

    private HttpResponse<String> post(TestUser by, String path, String body, String key) {
        return postJson(path, key == null ? Map.of("Authorization", by.authorization())
                : Map.of("Authorization", by.authorization(), Idempotency.HEADER, key), body);
    }

    private HttpResponse<String> goOnline(TestDriver driver) {
        return postJson("/v1/drivers/me/online", Map.of("Authorization", driver.authorization(), Idempotency.HEADER,
                UUID.randomUUID().toString()), "{\"vehicle_id\": \"" + driver.vehicleId() + "\"}");
    }

    private String availability(TestDriver driver) {
        return jdbc.sql("SELECT status FROM dispatch.driver_availability WHERE driver_id = :id")
                .param("id", driver.id()).query(String.class).single();
    }

    private boolean offlineAfterRide(TestDriver driver) {
        return jdbc.sql("SELECT offline_after_ride FROM dispatch.driver_availability WHERE driver_id = :id")
                .param("id", driver.id()).query(Boolean.class).single();
    }

    private boolean suspended(TestDriver driver) {
        return jdbc.sql("SELECT suspended FROM driver.drivers WHERE id = :id").param("id", driver.id())
                .query(Boolean.class).single();
    }

    private String sessionReason(TestDriver driver) {
        return jdbc.sql("""
                        SELECT offline_reason FROM dispatch.driver_sessions WHERE driver_id = :id
                        ORDER BY online_at DESC LIMIT 1
                        """)
                .param("id", driver.id()).query(String.class).single();
    }

    private List<String> statusChanges(TestDriver driver) {
        return jdbc.sql("""
                        SELECT kind || ' ' || from_value || '→' || to_value || ': ' || reason
                        FROM driver.status_changes WHERE driver_id = :id AND kind <> 'VERIFICATION'
                        ORDER BY occurred_at, kind DESC
                        """)
                .param("id", driver.id()).query(String.class).list();
    }

    private List<String> audited(TestDriver driver) {
        return jdbc.sql("""
                        SELECT action || ' by ' || actor_type || ': ' || reason FROM audit.audit_log
                        WHERE entity_type = 'driver' AND entity_id = :id AND action IN ('driver.suspend',
                                                                                         'driver.reinstate')
                        ORDER BY occurred_at, id
                        """)
                .param("id", driver.id().toString()).query(String.class).list();
    }

    private long sessionsWaitingForALock() {
        return jdbc.sql("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE wait_event_type = 'Lock' AND datname = current_database()
                        """)
                .query(Long.class)
                .single();
    }
}
