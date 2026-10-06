package com.ridehailing.operations;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static com.ridehailing.support.TestRides.asApi;
import static com.ridehailing.support.TestRides.asWorker;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.Poller;
import com.ridehailing.platform.outbox.EventDelivery;
import com.ridehailing.ride.RideView;
import com.ridehailing.ride.app.DriverCommands;
import com.ridehailing.ride.db.FlagRepository;
import com.ridehailing.ride.db.FlagRepository.FlagKind;
import com.ridehailing.rider.app.RiderProfiles;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestDrivers.TestDriver;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestUsers;
import com.ridehailing.support.TestUsers.TestUser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Phase 11's exit criterion (FR-O1, FR-DS6, LLD §13.5): a ride's timeline answers "why did this take so long?" from
 * the data alone.
 */
class RideTimelineTests extends IntegrationTest {

    private static final String TIMELINE = "/v1/ops/rides/{ride_id}/timeline";
    private static final double METRES_PER_DEGREE = 6_371_008.8 * Math.PI / 180;

    @Autowired
    TestCities cities;

    @Autowired
    TestPrices prices;

    @Autowired
    TestRides rides;

    @Autowired
    TestUsers users;

    @Autowired
    RiderProfiles profiles;

    @Autowired
    DispatchApi dispatch;

    @Autowired
    DriverCommands driverCommands;

    @Autowired
    EventDelivery events;

    @Autowired
    ObjectProvider<Poller> pollers;

    @Autowired
    FlagRepository flags;

    @Autowired
    JdbcClient jdbc;

    /**
     * One ride through a search that finds nobody, an offer that expires unseen, an offer declined, an acceptance,
     * the driver's cancellation, a second search and acceptance, the trip, its fare and an operations refund.
     */
    @Test
    void aRidesTimelineExplainsItsWaitForADriverFromItsDataAlone() {
        abandonOtherTestsPayments();
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        GeoPoint pickup = city.at(0.1, 0.1);
        TestUser rider = rides.rider("Asha");
        profiles.setDefaultPaymentMethod(rider.id(),
                profiles.addPaymentMethod(rider.id(), "CARD", "tok_ok", "Visa •• 4242").id());
        RideView ride = rides.book(rider.id(), city, pickup, "MINI");
        rides.onlyDueIn(city.id());
        rides.search();
        TestDriver unseen = rides.onlineAt(city, "MINI", north(pickup, 100));
        UUID expiring = searchNow(ride);
        rides.fire("OFFER_EXPIRY", expiring);
        TestDriver decliner = rides.onlineAt(city, "MINI", north(pickup, 200));
        UUID declined = searchNow(ride);
        asApi(() -> dispatch.decline(declined, decliner.id()));
        TestDriver canceller = rides.onlineAt(city, "MINI", north(pickup, 300));
        UUID first = searchNow(ride);
        asApi(() -> dispatch.accept(first, canceller.id()));
        assertThat(postJson("/v1/rides/" + ride.id() + "/cancel", Map.of("Authorization", canceller.authorization(),
                Idempotency.HEADER, UUID.randomUUID().toString()), "{}").statusCode()).isEqualTo(200);
        TestDriver driver = rides.onlineAt(city, "MINI", north(pickup, 400));
        UUID second = searchNow(ride);
        asApi(() -> dispatch.accept(second, driver.id()));
        String pin = jdbc.sql("SELECT pin FROM ride.rides WHERE id = :id").param("id", ride.id())
                .query(String.class).single();
        asApi(() -> {
            driverCommands.arrive(driver.id(), ride.id());
            driverCommands.start(driver.id(), ride.id(), pin);
            return driverCommands.complete(driver.id(), ride.id());
        });
        deliver(ride.id());
        assertThat(poll("payment-sender")).as("the fare charged").isEqualTo(1);
        UUID charge = jdbc.sql("SELECT id FROM payment.charges WHERE ride_id = :id").param("id", ride.id())
                .query(UUID.class).single();
        TestUser ops = users.create(UserRole.OPS);
        assertThat(postJson("/v1/ops/charges/" + charge + "/refunds", Map.of("Authorization", ops.authorization(),
                Idempotency.HEADER, UUID.randomUUID().toString()),
                "{\"amount_paise\": 5000, \"reason\": \"Long wait for a driver\"}").statusCode()).isEqualTo(202);
        assertThat(poll("payment-sender")).as("the refund sent").isEqualTo(1);
        deliver(ride.id());

        JsonNode timeline = assertAnswered("GET", TIMELINE, getAs(ops.authorization(),
                "/v1/ops/rides/" + ride.id() + "/timeline"), 200);

        assertThat(timeline.get("ride_id").asString()).isEqualTo(ride.id().toString());
        List<JsonNode> entries = new ArrayList<>();
        timeline.get("entries").forEach(entries::add);
        assertThat(entries).isSortedAccordingTo(Comparator.comparing(entry -> Instant.parse(entry.get("at")
                .asString())));
        Map<String, String> who = Map.of(unseen.id().toString(), "A", decliner.id().toString(), "B",
                canceller.id().toString(), "C", driver.id().toString(), "D");
        assertThat(story(entries, who)).containsExactly(
                "booked: SEARCHING",
                "search: NO_CANDIDATES",
                "search: OFFERED to A",
                "offer to A: EXPIRED, never seen",
                "search: OFFERED to B",
                "offer to B: DECLINED (DRIVER)",
                "search: OFFERED to C",
                "offer to C: ACCEPTED",
                "SEARCHING → DRIVER_ASSIGNED",
                "DRIVER_ASSIGNED → SEARCHING by DRIVER",
                "search: OFFERED to D",
                "offer to D: ACCEPTED",
                "SEARCHING → DRIVER_ASSIGNED",
                "DRIVER_ASSIGNED → DRIVER_ARRIVED",
                "DRIVER_ARRIVED → IN_TRIP",
                "IN_TRIP → COMPLETED");
        Instant booked = at(entries, "TRANSITION", "SEARCHING: BOOK by RIDER");
        Instant assigned = entries.stream().filter(entry -> entry.get("kind").asString().equals("TRANSITION")
                && entry.get("summary").asString().startsWith("SEARCHING → DRIVER_ASSIGNED"))
                .map(entry -> Instant.parse(entry.get("at").asString())).max(Comparator.naturalOrder()).orElseThrow();
        assertThat(Duration.between(booked, assigned)).as("the wait for the driver who took the ride").isPositive();
        assertThat(summaries(entries, "OFFER")).anyMatch(summary -> summary.endsWith(", never seen"));
        assertThat(summaries(entries, "DISPATCH_DECISION").getFirst()).contains("NO_CANDIDATES, 0 candidates");
        assertThat(summaries(entries, "DISPATCH_DECISION").get(1)).endsWith(": OFFERED, 1 candidates, offered to driver "
                + unseen.id());
        assertThat(summaries(entries, "CHARGE")).singleElement().asString()
                .startsWith("FARE of INR ").endsWith(" by CARD: SUCCEEDED");
        assertThat(summaries(entries, "REFUND")).containsExactly(
                "Refund of INR 50.00: SUCCEEDED (Long wait for a driver)");
        assertThat(summaries(entries, "EVENT")).contains("RideRequested from ride/api",
                "OfferExpired from dispatch/dispatch", "DriverUnassigned from ride/api", "TripCompleted from ride/api",
                "ChargeSucceeded from payment/worker", "RefundSucceeded from payment/worker");
        assertThat(summaries(entries, "NOTIFICATION")).contains("DRIVER_UNASSIGNED to the rider: PENDING",
                "TRIP_COMPLETED to driver " + driver.id() + ": PENDING");
        assertThat(summaries(entries, "AUDIT")).contains("refund.requested by OPS (Long wait for a driver)");
        assertThat(timeline.toString()).as("never the PIN").doesNotContain("\"pin\"").doesNotContain(pin + "\"");
    }

    @Test
    void theTimelineShowsTheRidesFlagsAndWhoResolvedThem() {
        TestCity city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        RideView ride = rides.book(rides.rider("Ravi").id(), city, city.at(0.1, 0.1), "MINI");
        flags.open(ride.id(), FlagKind.STUCK, "{\"status\": \"SEARCHING\"}");
        UUID flag = jdbc.sql("SELECT id FROM ride.flags WHERE ride_id = :id").param("id", ride.id())
                .query(UUID.class).single();
        TestUser ops = users.create(UserRole.OPS);
        assertThat(postJson("/v1/ops/flags/" + flag + "/resolve", Map.of("Authorization", ops.authorization()),
                "{\"resolution\": \"Rider called\"}").statusCode()).isEqualTo(200);

        JsonNode timeline = assertAnswered("GET", TIMELINE, getAs(ops.authorization(),
                "/v1/ops/rides/" + ride.id() + "/timeline"), 200);

        List<JsonNode> entries = new ArrayList<>();
        timeline.get("entries").forEach(entries::add);
        assertThat(summaries(entries, "FLAG")).containsExactly("STUCK opened", "STUCK resolved: Rider called");
        assertThat(summaries(entries, "AUDIT")).containsExactly("flag.resolve by OPS (Rider called)");
        assertThat(summaries(entries, "TRANSITION")).containsExactly("SEARCHING: BOOK by RIDER");
    }

    @Test
    void onlyOperationsReadTimelinesOfRidesThatExist() {
        TestUser ops = users.create(UserRole.OPS);
        TestUser rider = users.create(UserRole.RIDER);

        assertProblem("GET", TIMELINE, getAs(ops.authorization(), "/v1/ops/rides/" + UUID.randomUUID() + "/timeline"),
                404, "NOT_FOUND");
        assertProblem("GET", TIMELINE, getAs(rider.authorization(), "/v1/ops/rides/" + UUID.randomUUID()
                + "/timeline"), 403, "FORBIDDEN");
    }

    /** Transitions, decisions and offers, in the timeline's order, with the drivers by letter. */
    private static List<String> story(List<JsonNode> entries, Map<String, String> who) {
        List<String> story = new ArrayList<>();
        for (JsonNode entry : entries) {
            JsonNode data = entry.get("data");
            switch (entry.get("kind").asString()) {
                case "TRANSITION" -> story.add(data.has("from_status")
                        ? data.get("from_status").asString() + " → " + data.get("to_status").asString()
                                + (data.get("to_status").asString().equals("SEARCHING")
                                        ? " by " + data.get("actor_type").asString() : "")
                        : "booked: " + data.get("to_status").asString());
                case "DISPATCH_DECISION" -> story.add("search: " + data.get("outcome").asString()
                        + (data.has("chosen_driver_id") ? " to " + who.get(data.get("chosen_driver_id").asString())
                                : ""));
                case "OFFER" -> story.add("offer to " + who.get(data.get("driver_id").asString()) + ": "
                        + data.get("status").asString()
                        + (data.has("end_reason") ? " (" + data.get("end_reason").asString() + ")" : "")
                        + (data.get("status").asString().equals("EXPIRED") && !data.has("seen_at")
                                ? ", never seen" : ""));
                default -> {
                }
            }
        }
        return story;
    }

    private static List<String> summaries(List<JsonNode> entries, String kind) {
        return entries.stream().filter(entry -> entry.get("kind").asString().equals(kind))
                .map(entry -> entry.get("summary").asString()).toList();
    }

    private static Instant at(List<JsonNode> entries, String kind, String summary) {
        return entries.stream().filter(entry -> entry.get("kind").asString().equals(kind)
                && entry.get("summary").asString().equals(summary))
                .map(entry -> Instant.parse(entry.get("at").asString())).findFirst().orElseThrow();
    }

    /** Makes the ride's task due and runs the attempt; answers the offer it made. */
    private UUID searchNow(RideView ride) {
        jdbc.sql("UPDATE dispatch.search_tasks SET due_at = now() WHERE ride_id = :id").param("id", ride.id()).update();
        rides.onlyDueIn(ride.cityId());
        rides.search();
        UUID offer = rides.pendingOffer(ride.id());
        assertThat(offer).as("an offer for ride %s", ride.id()).isNotNull();
        return offer;
    }

    private void deliver(UUID rideId) {
        asWorker(() -> {
            events.deliverAll(rideId);
            return null;
        });
    }

    /** Polls as a worker node does until nothing is left; answers how many units of work ran. */
    private int poll(String name) {
        Poller poller = pollers.stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow();
        return asWorker(() -> {
            int polled = 0;
            while (poller.poll()) {
                polled++;
            }
            return polled;
        });
    }

    /** The bean poller's executor would otherwise send attempts and refunds other tests left open first. */
    private void abandonOtherTestsPayments() {
        for (String table : List.of("payment.charge_attempts", "payment.refunds")) {
            jdbc.sql("UPDATE " + table + """
                     SET status = 'FAILED', failure_code = 'ABANDONED_BY_TEST', lease_until = NULL,
                        next_check_at = NULL, completed_at = now()
                    WHERE status IN ('PENDING', 'IN_FLIGHT', 'UNKNOWN')
                    """).update();
        }
    }

    private static GeoPoint north(GeoPoint from, double metres) {
        return new GeoPoint(from.lat() + metres / METRES_PER_DEGREE, from.lon());
    }
}
