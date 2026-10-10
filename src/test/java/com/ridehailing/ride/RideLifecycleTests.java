package com.ridehailing.ride;

import static com.ridehailing.support.EventContract.outboxEvents;
import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.MirrorState;
import com.ridehailing.location.LiveIndex.Status;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.EventContract;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestRides.AssignedRide;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** Every transition after assignment through the API (LLD §7.4–§7.8, ride lifecycle §3–§5), with its refusals. */
class RideLifecycleTests extends IntegrationTest {

    private static final double METRES_PER_DEGREE = 6_371_008.8 * Math.PI / 180;
    private static final String RIDE = "/v1/rides/{ride_id}";
    private static final String ARRIVE = "/v1/rides/{ride_id}/arrive";
    private static final String START = "/v1/rides/{ride_id}/start";
    private static final String COMPLETE = "/v1/rides/{ride_id}/complete";
    private static final String NO_SHOW = "/v1/rides/{ride_id}/no-show";
    private static final String CANCEL = "/v1/rides/{ride_id}/cancel";
    private static final String OPS_CANCEL = "/v1/ops/rides/{ride_id}/cancel";

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestRides rides;

    @Autowired
    private TestUsers users;

    @Autowired
    private LiveIndex index;

    @Autowired
    private JdbcClient jdbc;

    private TestCity city;
    private GeoPoint pickup;
    private AssignedRide ride;
    private TestDriver driver;
    private TestUser rider;

    @BeforeEach
    void setUp() {
        city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        pickup = city.at(0.1, 0.1);
        ride = rides.assigned(city, pickup, north(100));
        driver = ride.driver();
        rider = ride.rider();
    }

    @AfterEach
    void invariantsHold() {
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void arrivingNearThePickupMarksTheRideArrived() {
        JsonNode arrived = assertAnswered("POST", ARRIVE, command(driver, "arrive"), 200);

        assertThat(arrived.get("status").asString()).isEqualTo("DRIVER_ARRIVED");
        assertThat(arrived.has("arrived_at")).isTrue();
        assertThat(arrived.has("pin")).isFalse();
        assertThat(outboxEvents(jdbc, "DriverArrived", ride.id())).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("distance_to_pickup_m").asInt()).isBetween(99, 101);
        });
        assertThat(flags()).isEmpty();
        JsonNode again = assertAnswered("POST", ARRIVE, command(driver, "arrive"), 200);
        assertThat(again.get("version").asInt()).isEqualTo(arrived.get("version").asInt());
        assertThat(log()).containsExactly("SEARCHING BOOK RIDER", "DRIVER_ASSIGNED ACCEPT DRIVER",
                "DRIVER_ARRIVED ARRIVE DRIVER");
    }

    @Test
    void arrivingFarFromThePickupIsFlaggedButStillCounts() {
        rides.relocate(driver, 2, north(301));

        assertAnswered("POST", ARRIVE, command(driver, "arrive"), 200);

        assertThat(flags()).containsExactly("ARRIVED_FAR {\"distance_m\": 301}");
    }

    @Test
    void arrivingThreeHundredMetresAwayIsNearEnough() {
        rides.relocate(driver, 2, north(300));

        assertAnswered("POST", ARRIVE, command(driver, "arrive"), 200);

        assertThat(flags()).isEmpty();
        assertThat(outboxEvents(jdbc, "DriverArrived", ride.id())).singleElement().satisfies(event ->
                assertThat(event.get("payload").get("distance_to_pickup_m").asInt()).isEqualTo(300));
    }

    @Test
    void arrivingWithoutALivePositionIsFlaggedAsUnknown() {
        index.mirror(city.id(), driver.id(), new MirrorState(Status.OFFLINE, 1_000, null, null));

        assertAnswered("POST", ARRIVE, command(driver, "arrive"), 200);

        assertThat(flags()).containsExactly("ARRIVED_FAR {\"position\": \"unknown\"}");
        assertThat(outboxEvents(jdbc, "DriverArrived", ride.id())).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").has("distance_to_pickup_m")).isFalse();
        });
        index.mirror(city.id(), driver.id(), new MirrorState(Status.ASSIGNED, 1_001, "MINI", ride.id()));
    }

    @Test
    void theRidersPinStartsTheTripAndOnlyOnce() {
        assertAnswered("POST", ARRIVE, command(driver, "arrive"), 200);
        JsonNode seenByRider = assertAnswered("GET", RIDE, call("GET", rider.authorization(), path(""), null), 200);
        assertThat(seenByRider.get("pin").asString()).isEqualTo(ride.pin());

        JsonNode started = assertAnswered("POST", START, start(ride.pin()), 200);

        assertThat(started.get("status").asString()).isEqualTo("IN_TRIP");
        assertThat(started.has("pin")).isFalse();
        assertThat(availability(driver)).isEqualTo("ON_TRIP");
        assertThat(index.mirrored(city.id()).get(driver.id()).status()).isEqualTo(Status.ON_TRIP);
        assertThat(outboxEvents(jdbc, "TripStarted", ride.id())).singleElement()
                .satisfies(EventContract::assertConforms);
        assertThat(assertAnswered("GET", RIDE, call("GET", rider.authorization(), path(""), null), 200).has("pin"))
                .as("the PIN is gone once the trip starts").isFalse();
        assertThat(assertAnswered("POST", START, start(wrong(ride.pin())), 200).get("version").asInt())
                .as("a started trip answers any start").isEqualTo(started.get("version").asInt());
    }

    @Test
    void fiveWrongPinsLockTheStartAndARetryDoesntCount() {
        assertAnswered("POST", ARRIVE, command(driver, "arrive"), 200);
        int version = version();
        String firstKey = UUID.randomUUID().toString();
        HttpResponse<String> first = postJson(path("/start"), headers(driver.authorization(), firstKey),
                "{\"pin\": \"" + wrong(ride.pin()) + "\"}");
        JsonNode refused = assertProblem("POST", START, first, 422, "WRONG_PIN");
        assertThat(refused.get("attempts_left").asInt()).isEqualTo(4);
        assertThat(refused.get("instance").asString()).isEqualTo(path("/start"));

        HttpResponse<String> retried = postJson(path("/start"), headers(driver.authorization(), firstKey),
                "{\"pin\": \"" + wrong(ride.pin()) + "\"}");
        assertThat(assertProblem("POST", START, retried, 422, "WRONG_PIN").get("attempts_left").asInt()).isEqualTo(4);
        assertThat(retried.headers().firstValue(Idempotency.REPLAYED_HEADER)).hasValue("true");
        assertThat(json(retried)).as("the stored answer, as first given").isEqualTo(json(first));
        for (int left = 3; left >= 0; left--) {
            assertThat(flags()).as("not locked with %d attempts left", left + 1).isEmpty();
            assertThat(assertProblem("POST", START, start(wrong(ride.pin())), 422, "WRONG_PIN")
                    .get("attempts_left").asInt()).isEqualTo(left);
        }

        assertProblem("POST", START, start(ride.pin()), 409, "PIN_LOCKED");
        assertThat(flags()).containsExactly("PIN_LOCKED {}");
        assertThat(version()).as("wrong PINs aren't transitions").isEqualTo(version);
        assertThat(jdbc.sql("SELECT pin_attempts FROM ride.rides WHERE id = :id").param("id", ride.id())
                .query(Integer.class).single()).isEqualTo(5);
        assertAnswered("POST", CANCEL, command(driver, "cancel"), 200);
        assertThat(rides.rideStatus(ride.id())).isEqualTo("CANCELLED_BY_DRIVER");
    }

    @Test
    void theTripCanStartOnlyAfterArrival() {
        JsonNode refused = assertProblem("POST", START, start(ride.pin()), 409, "INVALID_TRANSITION");

        assertThat(refused.get("current_status").asString()).isEqualTo("DRIVER_ASSIGNED");
        assertProblem("POST", COMPLETE, command(driver, "complete"), 409, "INVALID_TRANSITION");
        assertProblem("POST", NO_SHOW, command(driver, "no-show"), 409, "INVALID_TRANSITION");
    }

    @Test
    void aNoShowWaitsForThePickupWaitThenChargesTheNoShowFee() {
        JsonNode arrived = assertAnswered("POST", ARRIVE, command(driver, "arrive"), 200);
        JsonNode early = assertProblem("POST", NO_SHOW, command(driver, "no-show"), 409, "NO_SHOW_TOO_EARLY");
        assertThat(java.time.Instant.parse(early.get("available_at").asString()))
                .isEqualTo(java.time.Instant.parse(arrived.get("arrived_at").asString()).plusSeconds(300));
        shift("arrived_at", 301);

        JsonNode ended = assertAnswered("POST", NO_SHOW, command(driver, "no-show"), 200);

        assertThat(ended.get("status").asString()).isEqualTo("CANCELLED_BY_DRIVER");
        assertThat(ended.get("cancellation").get("cancelled_by").asString()).isEqualTo("DRIVER");
        assertThat(ended.get("cancellation").get("reason").asString()).isEqualTo("NO_SHOW");
        assertThat(ended.get("cancellation").get("fee").get("purpose").asString()).isEqualTo("NO_SHOW_FEE");
        assertThat(ended.get("cancellation").get("fee").get("amount").get("amount_paise").asLong()).isEqualTo(7_500);
        assertThat(outboxEvents(jdbc, "RideCancelled", ride.id())).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            JsonNode fee = event.get("payload").get("fee");
            assertThat(fee.get("amount").get("amount_paise").asLong()).isEqualTo(7_500);
            assertThat(fee.get("commission").get("amount_paise").asLong()).isEqualTo(1_500);
        });
        assertThat(availability(driver)).isEqualTo("AVAILABLE");
        assertThat(assertAnswered("POST", NO_SHOW, command(driver, "no-show"), 200).get("version").asInt())
                .isEqualTo(ended.get("version").asInt());
    }

    @Test
    void anOfflineAppsStartAndCompletionKeepTheirDeviceTimes() {
        assertAnswered("POST", ARRIVE, command(driver, "arrive"), 200);

        assertAnswered("POST", START, postJson(path("/start"), headers(driver.authorization(),
                UUID.randomUUID().toString()), """
                {"pin": "%s", "device_time": "2026-10-07T09:30:00.123Z"}""".formatted(ride.pin())), 200);
        assertAnswered("POST", COMPLETE, postJson(path("/complete"), headers(driver.authorization(),
                UUID.randomUUID().toString()), "{\"device_time\": \"2026-10-07T09:50:00Z\"}"), 200);

        assertThat(jdbc.sql("""
                        SELECT start_device_time::text || ' ' || complete_device_time::text FROM ride.rides WHERE id = :id
                        """).param("id", ride.id()).query(String.class).single())
                .isEqualTo("2026-10-07 09:30:00.123+00 2026-10-07 09:50:00+00");
        assertThat(jdbc.sql("""
                        SELECT command || ' ' || coalesce(device_time::text, '-') FROM ride.transitions
                        WHERE ride_id = :id ORDER BY version
                        """).param("id", ride.id()).query(String.class).list())
                .endsWith("ARRIVE -", "START 2026-10-07 09:30:00.123+00", "COMPLETE 2026-10-07 09:50:00+00");
    }

    @Test
    void completingEndsTheTripAtTheQuotedFare() {
        assertAnswered("POST", ARRIVE, command(driver, "arrive"), 200);
        assertAnswered("POST", START, start(ride.pin()), 200);
        long quotedFare = jdbc.sql("SELECT fare_paise FROM ride.rides WHERE id = :id").param("id", ride.id())
                .query(Long.class).single();

        JsonNode completed = assertAnswered("POST", COMPLETE, command(driver, "complete"), 200);

        assertThat(completed.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(completed.get("completed_at")).isEqualTo(completed.get("ended_at"));
        assertThat(completed.get("fare").get("amount_paise").asLong()).isEqualTo(quotedFare);
        assertThat(outboxEvents(jdbc, "TripCompleted", ride.id())).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("fare").get("amount_paise").asLong()).isEqualTo(quotedFare);
        });
        assertThat(availability(driver)).isEqualTo("AVAILABLE");
        assertThat(index.mirrored(city.id()).get(driver.id())).isEqualTo(
                new MirrorState(Status.AVAILABLE, availabilityVersion(driver), "MINI", null));
        assertThat(assertAnswered("POST", COMPLETE, command(driver, "complete"), 200).get("version").asInt())
                .isEqualTo(completed.get("version").asInt());
        assertProblem("POST", CANCEL, command(rider, "cancel"), 409, "INVALID_TRANSITION");
        assertProblem("POST", ARRIVE, command(driver, "arrive"), 409, "INVALID_TRANSITION");
    }

    @Test
    void theRiderCancelsFreeWithinTwoMinutesOfAssignment() {
        shift("assigned_at", 119);

        JsonNode cancelled = assertAnswered("POST", CANCEL, command(rider, "cancel"), 200);

        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED_BY_RIDER");
        assertThat(cancelled.get("cancellation").get("cancelled_by").asString()).isEqualTo("RIDER");
        assertThat(cancelled.has("pin")).as("the rider's own view").isFalse();
        assertThat(cancelled.has("rider")).isFalse();
        assertThat(cancelled.get("cancellation").has("fee")).isFalse();
        assertThat(availability(driver)).isEqualTo("AVAILABLE");
        assertThat(outboxEvents(jdbc, "RideCancelled", ride.id())).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("cancelled_by").asString()).isEqualTo("RIDER");
            assertThat(event.get("payload").has("fee")).isFalse();
            assertThat(event.get("payload").get("driver_id").asString()).isEqualTo(driver.id().toString());
        });
        assertThat(assertAnswered("POST", CANCEL, command(rider, "cancel"), 200).get("version").asInt())
                .isEqualTo(cancelled.get("version").asInt());
    }

    @Test
    void theRiderPaysTheCancellationFeeAfterTheFreeWindow() {
        shift("assigned_at", 121);

        JsonNode cancelled = assertAnswered("POST", CANCEL, command(rider, "cancel"), 200);

        assertThat(cancelled.get("cancellation").get("fee").get("purpose").asString()).isEqualTo("CANCELLATION_FEE");
        assertThat(cancelled.get("cancellation").get("fee").get("amount").get("amount_paise").asLong())
                .isEqualTo(5_000);
        assertThat(outboxEvents(jdbc, "RideCancelled", ride.id())).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("fee").get("commission").get("amount_paise").asLong())
                    .isEqualTo(1_000);
        });
    }

    @Test
    void aLateDriverMakesTheCancellationFree() {
        shift("assigned_at", promisedEta() + 301);

        JsonNode cancelled = assertAnswered("POST", CANCEL, command(rider, "cancel"), 200);

        assertThat(cancelled.get("cancellation").has("fee")).isFalse();
    }

    @Test
    void aDriverNotYetLateKeepsTheFee() {
        shift("assigned_at", promisedEta() + 299);

        JsonNode cancelled = assertAnswered("POST", CANCEL, command(rider, "cancel"), 200);

        assertThat(cancelled.get("cancellation").get("fee").get("purpose").asString()).isEqualTo("CANCELLATION_FEE");
    }

    @Test
    void aDriverWhoArrivedOnTimeKeepsTheFeeEvenIfTheRiderWaitsLong() {
        assertAnswered("POST", ARRIVE, command(driver, "arrive"), 200);
        int eta = promisedEta();
        shift("assigned_at", eta + 400);
        shift("arrived_at", 400 - eta);

        JsonNode cancelled = assertAnswered("POST", CANCEL, command(rider, "cancel"), 200);

        assertThat(cancelled.get("cancellation").get("fee").get("purpose").asString()).isEqualTo("CANCELLATION_FEE");
        assertThat(availability(driver)).isEqualTo("AVAILABLE");
    }

    @Test
    void theDriverCancellingBeforeArrivalReturnsTheRideToTheSearch() {
        JsonNode released = assertAnswered("POST", CANCEL, command(driver, "cancel"), 200);

        assertThat(released.get("status").asString()).isEqualTo("SEARCHING");
        assertThat(released.has("rider")).isFalse();
        assertThat(released.has("driver")).isFalse();
        assertThat(released.has("pin")).isFalse();
        assertThat(jdbc.sql("""
                        SELECT search_generation || ' ' || reassign_count || ' ' || num_nulls(driver_id, vehicle_id,
                               offer_id, pin, promised_pickup_eta_s, driver_snapshot, assigned_at)
                        FROM ride.rides WHERE id = :id
                        """).param("id", ride.id()).query(String.class).single())
                .as("generation, reassignments, and the driver's seven fields cleared").isEqualTo("2 1 7");
        assertThat(outboxEvents(jdbc, "DriverUnassigned", ride.id())).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("reason").asString()).isEqualTo("DRIVER_CANCELLED");
            assertThat(event.get("payload").get("search_generation").asInt()).isEqualTo(2);
        });
        assertThat(availability(driver)).isEqualTo("AVAILABLE");
        assertThat(jdbc.sql("SELECT cancelled_after_accept FROM dispatch.driver_stats WHERE driver_id = :id")
                .param("id", driver.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT payload ->> 'generation' FROM platform.timers
                        WHERE kind = 'SEARCH_TIMEOUT' AND aggregate_id = :id
                        """).param("id", ride.id()).query(String.class).single()).isEqualTo("2");
        assertThat(jdbc.sql("""
                        SELECT extract(epoch FROM due_at - now()) FROM platform.timers
                        WHERE kind = 'SEARCH_TIMEOUT' AND aggregate_id = :id
                        """).param("id", ride.id()).query(Double.class).single())
                .as("the city's search timeout, from the cancellation").isBetween(170.0, 180.0);
        assertThat(jdbc.sql("SELECT priority FROM dispatch.search_tasks WHERE ride_id = :id").param("id", ride.id())
                .query(Integer.class).single()).isEqualTo(1);

        TestDriver next = rides.onlineAt(city, "MINI", north(400));
        rides.onlyDueIn(city.id());
        rides.search();
        assertThat(jdbc.sql("SELECT driver_id FROM dispatch.offers WHERE ride_id = :id AND status = 'PENDING'")
                .param("id", ride.id()).query(UUID.class).single()).as("the driver who cancelled is excluded")
                .isEqualTo(next.id());

        assertThat(assertAnswered("POST", CANCEL, command(driver, "cancel"), 200).get("status").asString())
                .isEqualTo("SEARCHING");
        assertProblem("POST", ARRIVE, command(driver, "arrive"), 409, "RIDE_REASSIGNED");
        assertProblem("GET", RIDE, call("GET", driver.authorization(), path(""), null), 404, "NOT_FOUND");
    }

    @Test
    void theDriverCancellingAtThePickupEndsTheRideAndFlagsIt() {
        assertAnswered("POST", ARRIVE, command(driver, "arrive"), 200);

        JsonNode released = assertAnswered("POST", CANCEL, postJson(path("/cancel"),
                headers(driver.authorization(), UUID.randomUUID().toString()), "{\"reason\": \"car broke down\"}"),
                200);

        assertThat(released.get("status").asString()).isEqualTo("CANCELLED_BY_DRIVER");
        assertThat(released.get("cancellation").get("cancelled_by").asString()).isEqualTo("DRIVER");
        assertThat(released.has("rider")).isFalse();
        assertThat(flags()).containsExactly("DRIVER_CANCELLED_AT_PICKUP {}");
        assertThat(outboxEvents(jdbc, "RideCancelled", ride.id())).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("cancelled_by").asString()).isEqualTo("DRIVER");
            assertThat(event.get("payload").has("fee")).isFalse();
        });
        assertThat(availability(driver)).isEqualTo("AVAILABLE");
        assertThat(assertAnswered("POST", CANCEL, command(driver, "cancel"), 200).get("status").asString())
                .isEqualTo("CANCELLED_BY_DRIVER");
    }

    @Test
    void operationsCancelARideInTripWithAFeeUpToTheRule() {
        assertAnswered("POST", ARRIVE, command(driver, "arrive"), 200);
        assertAnswered("POST", START, start(ride.pin()), 200);
        TestUser ops = users.create(UserRole.OPS);
        int version = version();

        assertProblem("POST", OPS_CANCEL, opsCancel(ops, "{\"reason\": \"safety\", \"fee\": {\"purpose\": "
                + "\"CANCELLATION_FEE\", \"amount_paise\": 5001}}"), 422, "FEE_EXCEEDS_RULE");
        assertThat(version()).isEqualTo(version);
        JsonNode cancelled = assertAnswered("POST", OPS_CANCEL, opsCancel(ops, "{\"reason\": \"safety\", \"fee\": "
                + "{\"purpose\": \"CANCELLATION_FEE\", \"amount_paise\": 3000}}"), 200);

        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED_BY_SYSTEM");
        assertThat(cancelled.get("cancellation").get("cancelled_by").asString()).isEqualTo("SYSTEM");
        assertThat(cancelled.get("cancellation").get("fee").get("amount").get("amount_paise").asLong())
                .isEqualTo(3_000);
        assertThat(availability(driver)).isEqualTo("AVAILABLE");
        assertThat(log()).last().isEqualTo("CANCELLED_BY_SYSTEM CANCEL OPS");
        assertProblem("POST", OPS_CANCEL, opsCancel(ops, "{\"reason\": \"again\"}"), 409, "INVALID_TRANSITION");
        assertProblem("POST", OPS_CANCEL, postJson("/v1/ops/rides/" + UUID.randomUUID() + "/cancel",
                headers(ops.authorization(), UUID.randomUUID().toString()), "{\"reason\": \"x\"}"), 404, "NOT_FOUND");
        assertProblem("POST", OPS_CANCEL, opsCancel(rider, "{\"reason\": \"x\"}"), 403, "FORBIDDEN");
        assertProblem("POST", OPS_CANCEL, opsCancel(ops, "{\"reason\": \"\"}"), 400, "VALIDATION_FAILED");
    }

    @Test
    void operationsCancelASearchingRideAndItsOffer() {
        AssignedRide other = rides.assigned(city, city.at(0.2, 0.2), city.at(0.2, 0.2));
        TestUser ops = users.create(UserRole.ADMIN);
        assertAnswered("POST", CANCEL, command(other.driver(), other.id(), "cancel"), 200);
        TestDriver next = rides.onlineAt(city, "MINI", city.at(0.2, 0.2));
        rides.onlyDueIn(city.id());
        rides.search();
        UUID offer = rides.pendingOffer(other.id());

        JsonNode cancelled = assertAnswered("POST", OPS_CANCEL, postJson("/v1/ops/rides/" + other.id() + "/cancel",
                headers(ops.authorization(), UUID.randomUUID().toString()), "{\"reason\": \"duplicate\"}"), 200);

        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED_BY_SYSTEM");
        assertThat(log(other.id())).last().isEqualTo("CANCELLED_BY_SYSTEM CANCEL ADMIN");
        assertThat(rides.offerStatus(offer)).isEqualTo("WITHDRAWN");
        assertThat(outboxEvents(jdbc, "OfferWithdrawn", offer)).singleElement().satisfies(event -> {
            EventContract.assertConforms(event);
            assertThat(event.get("payload").get("reason").asString()).isEqualTo("OPS_CANCELLED");
        });
        assertThat(availability(next)).isEqualTo("AVAILABLE");
        assertThat(rides.timers().exists("SEARCH_TIMEOUT", other.id())).isFalse();
        assertThat(rides.timers().exists("OFFER_EXPIRY", offer)).isFalse();
    }

    @Test
    void aDriverSuspendedDuringTheRideGoesOfflineWhenItEnds() {
        assertAnswered("POST", ARRIVE, command(driver, "arrive"), 200);
        assertAnswered("POST", START, start(ride.pin()), 200);
        jdbc.sql("UPDATE dispatch.driver_availability SET offline_after_ride = true WHERE driver_id = :id")
                .param("id", driver.id()).update();

        assertAnswered("POST", COMPLETE, command(driver, "complete"), 200);

        assertThat(availability(driver)).isEqualTo("OFFLINE");
        assertThat(outboxEvents(jdbc, "DriverWentOffline", driver.id())).singleElement().satisfies(event ->
                assertThat(event.get("payload").get("reason").asString()).isEqualTo("SUSPENDED"));
    }

    @Test
    void onlyTheRidesDriverMayActOnIt() {
        TestDriver stranger = rides.onlineAt(city, "MINI", north(5_000));

        assertProblem("POST", ARRIVE, command(stranger, ride.id(), "arrive"), 404, "NOT_FOUND");
        assertProblem("POST", CANCEL, command(stranger, ride.id(), "cancel"), 404, "NOT_FOUND");
        assertProblem("POST", ARRIVE, command(rider, "arrive"), 403, "FORBIDDEN");
        assertProblem("POST", START, postJson(path("/start"), headers(driver.authorization(),
                UUID.randomUUID().toString()), "{\"pin\": \"12\"}"), 400, "VALIDATION_FAILED");
    }

    private HttpResponse<String> start(String pin) {
        return postJson(path("/start"), headers(driver.authorization(), UUID.randomUUID().toString()),
                "{\"pin\": \"" + pin + "\"}");
    }

    private HttpResponse<String> command(TestDriver who, String action) {
        return command(who, ride.id(), action);
    }

    private HttpResponse<String> command(TestDriver who, UUID rideId, String action) {
        return postJson("/v1/rides/" + rideId + "/" + action, headers(who.authorization(),
                UUID.randomUUID().toString()), "{}");
    }

    private HttpResponse<String> command(TestUser who, String action) {
        return postJson(path("/" + action), headers(who.authorization(), UUID.randomUUID().toString()), "{}");
    }

    private HttpResponse<String> opsCancel(TestUser who, String body) {
        return postJson("/v1/ops/rides/" + ride.id() + "/cancel", headers(who.authorization(),
                UUID.randomUUID().toString()), body);
    }

    private static Map<String, String> headers(String authorization, String key) {
        return Map.of("Authorization", authorization, Idempotency.HEADER, key);
    }

    private String path(String suffix) {
        return "/v1/rides/" + ride.id() + suffix;
    }

    /** Moves a stored time of the ride back, as if that much time had passed (§17.4). */
    private void shift(String column, int seconds) {
        jdbc.sql("UPDATE ride.rides SET %1$s = %1$s - make_interval(secs => :seconds) WHERE id = :id".formatted(column))
                .param("seconds", seconds).param("id", ride.id()).update();
    }

    private int version() {
        return jdbc.sql("SELECT version FROM ride.rides WHERE id = :id").param("id", ride.id()).query(Integer.class)
                .single();
    }

    private int promisedEta() {
        return jdbc.sql("SELECT promised_pickup_eta_s FROM ride.rides WHERE id = :id").param("id", ride.id())
                .query(Integer.class).single();
    }

    private List<String> log() {
        return log(ride.id());
    }

    private List<String> log(UUID rideId) {
        return jdbc.sql("""
                        SELECT to_status || ' ' || command || ' ' || actor_type FROM ride.transitions
                        WHERE ride_id = :id ORDER BY version
                        """).param("id", rideId).query(String.class).list();
    }

    private List<String> flags() {
        return jdbc.sql("SELECT kind || ' ' || details::text FROM ride.flags WHERE ride_id = :id ORDER BY created_at")
                .param("id", ride.id()).query(String.class).list();
    }

    private String availability(TestDriver who) {
        return jdbc.sql("SELECT status FROM dispatch.driver_availability WHERE driver_id = :id")
                .param("id", who.id()).query(String.class).single();
    }

    private long availabilityVersion(TestDriver who) {
        return jdbc.sql("SELECT version FROM dispatch.driver_availability WHERE driver_id = :id")
                .param("id", who.id()).query(Long.class).single();
    }

    private static String wrong(String pin) {
        return "%04d".formatted((Integer.parseInt(pin) + 1) % 10_000);
    }

    private GeoPoint north(double metres) {
        return new GeoPoint(pickup.lat() + metres / METRES_PER_DEGREE, pickup.lon());
    }
}
