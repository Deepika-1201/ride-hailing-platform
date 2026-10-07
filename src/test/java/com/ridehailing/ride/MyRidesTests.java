package com.ridehailing.ride;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static com.ridehailing.support.TestRides.asApi;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.outbox.EventDelivery;
import com.ridehailing.ride.app.DriverCommands;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.UserRole;
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

/** LLD §13.6: each party's history and active ride, the driver's earnings per trip, and the rider's receipt. */
class MyRidesTests extends IntegrationTest {

    private static final String RIDER_RIDES = "/v1/riders/me/rides";
    private static final String RIDER_ACTIVE = "/v1/riders/me/active-ride";
    private static final String DRIVER_RIDES = "/v1/drivers/me/rides";
    private static final String DRIVER_ACTIVE = "/v1/drivers/me/active-ride";
    private static final String RECEIPT = "/v1/rides/{ride_id}/receipt";
    private static final String CANCEL = "/v1/rides/{ride_id}/cancel";

    @Autowired
    private TestCities cities;

    @Autowired
    private TestPrices prices;

    @Autowired
    private TestRides rides;

    @Autowired
    private TestUsers users;

    @Autowired
    private DriverCommands commands;

    @Autowired
    private DispatchApi dispatch;

    @Autowired
    private EventDelivery events;

    @Autowired
    private JdbcClient jdbc;

    private TestCity city;
    private GeoPoint pickup;

    @BeforeEach
    void setUp() {
        city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        pickup = city.at(0.1, 0.1);
    }

    @AfterEach
    void invariantsHold() {
        assertThat(rides.violations(city.id())).isEmpty();
    }

    @Test
    void aRidersHistoryPagesTheirRidesNewestFirstInTheirOwnView() {
        TestUser rider = rides.rider("Asha");
        UUID cancelled = rides.book(rider.id(), city, pickup, "MINI").id();
        assertAnswered("POST", CANCEL, cancel(rider.authorization(), cancelled), 200);
        AssignedRide completed = rides.assigned(city, pickup, north(100), rider);
        complete(completed);
        // Nearer than the first driver, who is available again, so the offer goes to the new one.
        AssignedRide active = rides.assigned(city, pickup, north(50), rider);

        JsonNode first = assertAnswered("GET", RIDER_RIDES, getAs(rider.authorization(), RIDER_RIDES + "?limit=2"), 200);
        JsonNode second = assertAnswered("GET", RIDER_RIDES, getAs(rider.authorization(),
                RIDER_RIDES + "?limit=2&cursor=" + first.get("next_cursor").asString()), 200);

        assertThat(ids(first)).containsExactly(active.id(), completed.id());
        assertThat(ids(second)).containsExactly(cancelled);
        assertThat(second.has("next_cursor")).isFalse();
        JsonNode assigned = first.get("items").get(0);
        assertThat(assigned.get("pin").asString()).isEqualTo(active.pin());
        assertThat(assigned.get("driver").get("id").asString()).isEqualTo(active.driver().id().toString());
        assertThat(first.get("items").get(1).get("status").asString()).isEqualTo("COMPLETED");
        first.get("items").forEach(ride -> assertThat(ride.has("rider") || ride.has("earnings")).isFalse());
        assertThat(second.get("items").get(0).get("cancellation").get("cancelled_by").asString()).isEqualTo("RIDER");
        assertThat(assertAnswered("GET", RIDER_RIDES, getAs(rides.rider("Other").authorization(), RIDER_RIDES), 200)
                .get("items")).isEmpty();
    }

    @Test
    void aRidersActiveRideIsTheOneInProgressWithThePinUntilTheTripStarts() {
        TestUser rider = rides.rider("Asha");
        assertAnswered("GET", RIDER_ACTIVE, getAs(rider.authorization(), RIDER_ACTIVE), 204);
        UUID searching = rides.book(rider.id(), city, pickup, "MINI").id();

        JsonNode found = assertAnswered("GET", RIDER_ACTIVE, getAs(rider.authorization(), RIDER_ACTIVE), 200);

        assertThat(found.get("id").asString()).isEqualTo(searching.toString());
        assertThat(found.get("status").asString()).isEqualTo("SEARCHING");
        assertAnswered("POST", CANCEL, cancel(rider.authorization(), searching), 200);
        assertAnswered("GET", RIDER_ACTIVE, getAs(rider.authorization(), RIDER_ACTIVE), 204);

        AssignedRide assigned = rides.assigned(city, pickup, north(100), rider);
        assertThat(assertAnswered("GET", RIDER_ACTIVE, getAs(rider.authorization(), RIDER_ACTIVE), 200).get("pin")
                .asString()).isEqualTo(assigned.pin());
        asApi(() -> commands.arrive(assigned.driver().id(), assigned.id()));
        asApi(() -> commands.start(assigned.driver().id(), assigned.id(), assigned.pin()));
        JsonNode inTrip = assertAnswered("GET", RIDER_ACTIVE, getAs(rider.authorization(), RIDER_ACTIVE), 200);
        assertThat(inTrip.get("status").asString()).isEqualTo("IN_TRIP");
        assertThat(inTrip.has("pin")).isFalse();
        asApi(() -> commands.complete(assigned.driver().id(), assigned.id()));
        assertAnswered("GET", RIDER_ACTIVE, getAs(rider.authorization(), RIDER_ACTIVE), 204);
    }

    @Test
    void aDriversHistoryHasTheRidesTheyDriveOrEndedWithAndEachCompletedTripsEarnings() {
        AssignedRide completed = rides.assigned(city, pickup, north(100));
        TestDriver driver = completed.driver();
        complete(completed);
        AssignedRide left = assignAgain(driver, 2);
        assertAnswered("POST", CANCEL, cancel(driver.authorization(), left.id()), 200);
        AssignedRide current = assignAgain(driver, 3);

        JsonNode history = assertAnswered("GET", DRIVER_RIDES, getAs(driver.authorization(), DRIVER_RIDES), 200);
        JsonNode active = assertAnswered("GET", DRIVER_ACTIVE, getAs(driver.authorization(), DRIVER_ACTIVE), 200);

        assertThat(ids(history)).as("not the ride they left, which searches again").containsExactly(current.id(),
                completed.id());
        assertThat(active.get("id").asString()).isEqualTo(current.id().toString());
        assertThat(active.has("pin")).as("the driver's view").isFalse();
        assertThat(active.get("rider").get("first_name").asString()).isEqualTo("Rider");
        history.get("items").forEach(ride -> assertThat(ride.has("pin")).isFalse());
        assertThat(history.get("items").get(0).get("rider").get("first_name").asString()).isEqualTo("Rider");
        assertThat(history.get("items").get(0).has("earnings")).as("not completed").isFalse();
        long fare = history.get("items").get(1).get("fare").get("amount_paise").asLong();
        long commission = jdbc.sql("SELECT commission_paise FROM ride.rides WHERE id = :id")
                .param("id", completed.id()).query(Long.class).single();
        JsonNode earnings = history.get("items").get(1).get("earnings");
        assertThat(commission).isPositive();
        assertThat(earnings.get("gross").get("amount_paise").asLong()).isEqualTo(fare);
        assertThat(earnings.get("commission").get("amount_paise").asLong()).isEqualTo(commission);
        assertThat(earnings.get("net").get("amount_paise").asLong()).isEqualTo(fare - commission);
        assertThat(earnings.get("cash_collected").get("amount_paise").asLong()).as("a cash ride").isEqualTo(fare);

        assertAnswered("POST", CANCEL, cancel(current.rider().authorization(), current.id()), 200);
        assertAnswered("GET", DRIVER_ACTIVE, getAs(driver.authorization(), DRIVER_ACTIVE), 204);
    }

    @Test
    void historiesPageWithValidCursorsAndLimitsAndEachPartyReadsOnlyItsOwn() {
        TestUser rider = users.create(UserRole.RIDER);
        TestUser driver = users.create(UserRole.DRIVER);
        TestUser ops = users.create(UserRole.OPS);

        for (String path : List.of(RIDER_RIDES, DRIVER_RIDES)) {
            String caller = path.equals(RIDER_RIDES) ? rider.authorization() : driver.authorization();
            assertProblem("GET", path, getAs(caller, path + "?limit=0"), 400, "VALIDATION_FAILED");
            assertProblem("GET", path, getAs(caller, path + "?limit=101"), 400, "VALIDATION_FAILED");
            assertProblem("GET", path, getAs(caller, path + "?cursor=bm90LWEtY3Vyc29y"), 400, "VALIDATION_FAILED");
        }
        assertProblem("GET", RIDER_RIDES, getAs(driver.authorization(), RIDER_RIDES), 403, "FORBIDDEN");
        assertProblem("GET", RIDER_ACTIVE, getAs(ops.authorization(), RIDER_ACTIVE), 403, "FORBIDDEN");
        assertProblem("GET", DRIVER_RIDES, getAs(rider.authorization(), DRIVER_RIDES), 403, "FORBIDDEN");
        assertProblem("GET", DRIVER_ACTIVE, getAs(ops.authorization(), DRIVER_ACTIVE), 403, "FORBIDDEN");
        assertProblem("GET", RIDER_RIDES, get(port, RIDER_RIDES), 401, "UNAUTHENTICATED");
        assertThat(assertAnswered("GET", DRIVER_RIDES, getAs(driver.authorization(), DRIVER_RIDES), 200).get("items"))
                .as("a driver role without trips").isEmpty();
        assertAnswered("GET", DRIVER_ACTIVE, getAs(driver.authorization(), DRIVER_ACTIVE), 204);
    }

    @Test
    void theReceiptOfACompletedRideHasTheQuotedFareTheRouteAndTheCharges() {
        AssignedRide ride = rides.assigned(city, pickup, north(100));
        complete(ride);
        TestRides.asWorker(() -> {
            events.deliverAll(ride.id());
            return null;
        });

        JsonNode receipt = assertAnswered("GET", RECEIPT, receipt(ride.rider(), ride.id()), 200);

        Map<String, Object> quote = jdbc.sql("""
                        SELECT q.base_paise, q.distance_paise, q.time_paise, q.surge_paise, q.minimum_topup_paise,
                               q.booking_fee_paise, q.tax_paise, q.rounding_paise, q.total_paise, q.distance_m,
                               q.duration_s
                        FROM pricing.quotes q JOIN ride.rides r ON r.quote_id = q.id WHERE r.id = :ride
                        """)
                .param("ride", ride.id()).query().singleRow();
        JsonNode fare = receipt.get("fare");
        for (String part : List.of("base", "distance", "time", "surge", "minimum_topup", "booking_fee", "tax",
                "rounding", "total")) {
            assertThat(fare.get(part).get("amount_paise").asLong()).as(part)
                    .isEqualTo(((Number) quote.get(part + "_paise")).longValue());
        }
        assertThat(receipt.get("distance_m").asInt()).isEqualTo(quote.get("distance_m"));
        assertThat(receipt.get("duration_s").asInt()).isEqualTo(quote.get("duration_s"));
        assertThat(receipt.get("ride_id").asString()).isEqualTo(ride.id().toString());
        assertThat(receipt.get("payment").get("method_type").asString()).isEqualTo("CASH");
        assertThat(receipt.get("payment").get("charges")).singleElement().satisfies(charge -> {
            assertThat(charge.get("purpose").asString()).isEqualTo("FARE");
            assertThat(charge.get("status").asString()).isEqualTo("SUCCEEDED");
            assertThat(charge.get("amount")).isEqualTo(fare.get("total"));
        });
        assertThat(receipt.get("driver").get("id").asString()).isEqualTo(ride.driver().id().toString());
        assertThat(receipt.get("vehicle").get("id").asString()).isEqualTo(ride.driver().vehicleId().toString());
        assertThat(receipt.has("started_at") && receipt.has("completed_at")).isTrue();
    }

    @Test
    void aCardRidesEarningsCollectNoCashAndItsReceiptShowsTheCardCharge() {
        TestUser rider = rides.rider("Asha");
        JsonNode card = assertAnswered("POST", "/v1/riders/me/payment-methods", postJson(
                "/v1/riders/me/payment-methods", Map.of("Authorization", rider.authorization()),
                "{\"type\": \"CARD\", \"provider_token\": \"tok_ok\", \"display\": \"Visa 4242\"}"), 201);
        assertAnswered("POST", "/v1/riders/me/payment-methods/{method_id}/default", postJson(
                "/v1/riders/me/payment-methods/" + card.get("id").asString() + "/default",
                Map.of("Authorization", rider.authorization()), "{}"), 200);
        AssignedRide ride = rides.assigned(city, pickup, north(100), rider);
        complete(ride);
        TestRides.asWorker(() -> {
            events.deliverAll(ride.id());
            return null;
        });

        JsonNode trip = assertAnswered("GET", DRIVER_RIDES, getAs(ride.driver().authorization(), DRIVER_RIDES), 200)
                .get("items").get(0);
        JsonNode receipt = assertAnswered("GET", RECEIPT, receipt(rider, ride.id()), 200);

        assertThat(trip.get("earnings").get("cash_collected").get("amount_paise").asLong()).isZero();
        assertThat(trip.get("earnings").get("gross")).isEqualTo(trip.get("fare"));
        assertThat(receipt.get("payment").get("method_type").asString()).isEqualTo("CARD");
        assertThat(receipt.get("payment").get("charges")).singleElement().satisfies(charge -> {
            assertThat(charge.get("method_type").asString()).isEqualTo("CARD");
            assertThat(charge.get("ride_id").asString()).isEqualTo(ride.id().toString());
        });
    }

    @Test
    void aReceiptIsTheRidersAloneAndOnlyForACompletedRideThatKeptItsFare() {
        AssignedRide ride = rides.assigned(city, pickup, north(100));

        assertProblem("GET", RECEIPT, receipt(ride.rider(), ride.id()), 409, "RECEIPT_NOT_AVAILABLE");
        complete(ride);
        assertProblem("GET", RECEIPT, receipt(rides.rider("Other"), ride.id()), 404, "NOT_FOUND");
        assertProblem("GET", RECEIPT, receipt(ride.rider(), UUID.randomUUID()), 404, "NOT_FOUND");
        assertProblem("GET", RECEIPT, getAs(ride.driver().authorization(), "/v1/rides/" + ride.id() + "/receipt"),
                403, "FORBIDDEN");
        assertProblem("GET", RECEIPT, getAs(users.create(UserRole.OPS).authorization(),
                "/v1/rides/" + ride.id() + "/receipt"), 403, "FORBIDDEN");
        assertAnswered("GET", RECEIPT, receipt(ride.rider(), ride.id()), 200);

        jdbc.sql("UPDATE ride.rides SET fare_breakdown = NULL WHERE id = :id").param("id", ride.id()).update();
        assertProblem("GET", RECEIPT, receipt(ride.rider(), ride.id()), 409, "RECEIPT_NOT_AVAILABLE");
    }

    /** The driver, available again near the pickup, is offered a new rider's ride and accepts it. */
    private AssignedRide assignAgain(TestDriver driver, long seq) {
        rides.report(driver, seq, north(100));
        TestUser rider = rides.rider("Rider");
        RideView booked = rides.book(rider.id(), city, pickup, "MINI");
        rides.onlyDueIn(city.id());
        rides.search();
        UUID offer = rides.pendingOffer(booked.id());
        assertThat(offer).as("an offer to driver %s", driver.id()).isNotNull();
        asApi(() -> dispatch.accept(offer, driver.id()));
        return new AssignedRide(booked.id(), rider, driver, offer, null);
    }

    private void complete(AssignedRide ride) {
        asApi(() -> commands.arrive(ride.driver().id(), ride.id()));
        asApi(() -> commands.start(ride.driver().id(), ride.id(), ride.pin()));
        asApi(() -> commands.complete(ride.driver().id(), ride.id()));
    }

    private HttpResponse<String> cancel(String authorization, UUID rideId) {
        return postJson("/v1/rides/" + rideId + "/cancel", Map.of("Authorization", authorization, Idempotency.HEADER,
                UUID.randomUUID().toString()), "{}");
    }

    private HttpResponse<String> receipt(TestUser rider, UUID rideId) {
        return getAs(rider.authorization(), "/v1/rides/" + rideId + "/receipt");
    }

    private GeoPoint north(double metres) {
        return new GeoPoint(pickup.lat() + metres / 111_195.0, pickup.lon());
    }

    private static List<UUID> ids(JsonNode page) {
        return page.get("items").valueStream().map(ride -> UUID.fromString(ride.get("id").asString())).toList();
    }
}
