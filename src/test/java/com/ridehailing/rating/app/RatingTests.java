package com.ridehailing.rating.app;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static com.ridehailing.support.OpenApiContract.assertProblem;
import static com.ridehailing.support.TestRides.asApi;
import static com.ridehailing.support.TestRides.asWorker;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Idempotency;
import com.ridehailing.platform.Transactions;
import com.ridehailing.platform.outbox.EventDelivery;
import com.ridehailing.rating.RatingApi;
import com.ridehailing.rating.RatingApi.Party;
import com.ridehailing.rating.RatingApi.RatingSummary;
import com.ridehailing.rating.db.RatingRepository;
import com.ridehailing.ride.app.DriverCommands;
import com.ridehailing.shared.Ids;
import com.ridehailing.shared.UserRole;
import com.ridehailing.support.EventContract;
import com.ridehailing.support.Eventually;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.Postgis;
import com.ridehailing.support.TestCities;
import com.ridehailing.support.TestCities.TestCity;
import com.ridehailing.support.TestPrices;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.TestRides.AssignedRide;
import com.ridehailing.support.TestUsers;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** FR-RT1, FR-RT2, LLD §13.4: the window, once per side, who may rate, and the summary of the latest 100. */
class RatingTests extends IntegrationTest {

    private static final String RATING = "/v1/rides/{ride_id}/rating";

    @Autowired
    TestCities cities;

    @Autowired
    TestPrices prices;

    @Autowired
    TestRides rides;

    @Autowired
    TestUsers users;

    @Autowired
    DriverCommands drivers;

    @Autowired
    EventDelivery events;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Ratings ratings;

    @Autowired
    RatingRepository repository;

    @Autowired
    RatingApi api;

    @Autowired
    Transactions transactions;

    private TestCity city;

    @Test
    void theRiderAndTheDriverRateEachOtherOnce() {
        AssignedRide ride = completed();

        JsonNode byRider = assertAnswered("POST", RATING, rate(ride.rider().authorization(), ride.id(),
                "{\"stars\": 5, \"comment\": \"Smooth ride, safe driving\"}"), 201);
        JsonNode byDriver = assertAnswered("POST", RATING, rate(ride.driver().authorization(), ride.id(),
                "{\"stars\": 4}"), 201);

        assertThat(byRider.get("ride_id").asString()).isEqualTo(ride.id().toString());
        assertThat(byRider.get("rater_role").asString()).isEqualTo("RIDER");
        assertThat(byRider.get("stars").asInt()).isEqualTo(5);
        assertThat(byRider.get("comment").asString()).isEqualTo("Smooth ride, safe driving");
        assertThat(byDriver.get("rater_role").asString()).isEqualTo("DRIVER");
        assertThat(byDriver.has("comment")).isFalse();
        assertProblem("POST", RATING, rate(ride.rider().authorization(), ride.id(), "{\"stars\": 1}"), 409,
                "ALREADY_RATED");
        assertProblem("POST", RATING, rate(ride.driver().authorization(), ride.id(), "{\"stars\": 1}"), 409,
                "ALREADY_RATED");
        assertThat(api.summary(ride.driver().id(), Party.DRIVER)).isEqualTo(summary("5.00", 1));
        assertThat(api.summary(ride.rider().id(), Party.RIDER)).isEqualTo(summary("4.00", 1));
        assertThat(api.summary(ride.driver().id(), Party.RIDER)).isEqualTo(RatingSummary.NONE);
        assertThat(api.summary(ride.rider().id(), Party.DRIVER)).isEqualTo(RatingSummary.NONE);
    }

    @Test
    void eachRatingIsAnnouncedWithoutItsComment() {
        AssignedRide ride = completed();
        UUID byRider = UUID.fromString(json(rate(ride.rider().authorization(), ride.id(),
                "{\"stars\": 3, \"comment\": \"Took a longer route\"}")).get("id").asString());
        UUID byDriver = UUID.fromString(json(rate(ride.driver().authorization(), ride.id(), "{\"stars\": 5}"))
                .get("id").asString());

        JsonNode riders = EventContract.outboxEvents(jdbc, "RatingSubmitted", byRider).getFirst();
        JsonNode drivers = EventContract.outboxEvents(jdbc, "RatingSubmitted", byDriver).getFirst();

        EventContract.assertConforms(riders);
        EventContract.assertConforms(drivers);
        assertThat(riders.get("aggregate_type").asString()).isEqualTo("rating");
        assertThat(riders.get("producer").asString()).isEqualTo("rating/api");
        JsonNode payload = riders.get("payload");
        assertThat(payload.get("rating_id").asString()).isEqualTo(byRider.toString());
        assertThat(payload.get("ride_id").asString()).isEqualTo(ride.id().toString());
        assertThat(payload.get("rater_role").asString()).isEqualTo("RIDER");
        assertThat(payload.get("rater_id").asString()).isEqualTo(ride.rider().id().toString());
        assertThat(payload.get("ratee_id").asString()).isEqualTo(ride.driver().id().toString());
        assertThat(payload.get("stars").asInt()).isEqualTo(3);
        assertThat(payload.get("has_comment").asBoolean()).isTrue();
        assertThat(payload.toString()).doesNotContain("longer route");
        assertThat(drivers.get("payload").get("rater_role").asString()).isEqualTo("DRIVER");
        assertThat(drivers.get("payload").get("ratee_id").asString()).isEqualTo(ride.rider().id().toString());
        assertThat(drivers.get("payload").get("has_comment").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT partition_key FROM platform.outbox WHERE aggregate_id = :id")
                .param("id", byRider).query(UUID.class).single()).as("ordered with the ride's events")
                .isEqualTo(ride.id());
    }

    @Test
    void aRepeatedRequestAnswersTheSameRating() {
        AssignedRide ride = completed();
        String key = UUID.randomUUID().toString();

        JsonNode first = assertAnswered("POST", RATING, rate(ride.rider().authorization(), ride.id(),
                "{\"stars\": 4}", key), 201);
        JsonNode again = assertAnswered("POST", RATING, rate(ride.rider().authorization(), ride.id(),
                "{\"stars\": 4}", key), 201);

        assertThat(again).isEqualTo(first);
        assertProblem("POST", RATING, rate(ride.rider().authorization(), ride.id(), "{\"stars\": 2}", key), 422,
                "IDEMPOTENCY_KEY_REUSED");
        assertThat(jdbc.sql("SELECT count(*) FROM rating.ratings WHERE ride_id = :id").param("id", ride.id())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(api.summary(ride.driver().id(), Party.DRIVER).count()).isEqualTo(1);
    }

    @Test
    void aTripCompletedOpensTheWindowForSevenDays() {
        AssignedRide ride = rides.assigned(newCity(), city.at(0.1, 0.1), city.at(0.1, 0.1));
        assertProblem("POST", RATING, rate(ride.rider().authorization(), ride.id(), "{\"stars\": 5}"), 409,
                "RATING_NOT_OPEN");

        finish(ride);
        assertProblem("POST", RATING, rate(ride.rider().authorization(), ride.id(), "{\"stars\": 5}"), 409,
                "RATING_NOT_OPEN");
        deliver(ride);

        assertThat(jdbc.sql("""
                        SELECT w.closes_at - w.completed_at = interval '7 days' AND w.completed_at = r.completed_at
                        FROM rating.rating_windows w JOIN ride.rides r ON r.id = w.ride_id WHERE w.ride_id = :id
                        """).param("id", ride.id()).query(Boolean.class).single()).isTrue();
        assertAnswered("POST", RATING, rate(ride.rider().authorization(), ride.id(), "{\"stars\": 5}"), 201);
    }

    @Test
    void aClosedWindowTakesNoMoreRatings() {
        AssignedRide ride = completed();
        jdbc.sql("UPDATE rating.rating_windows SET closes_at = now() + interval '2 seconds' WHERE ride_id = :id")
                .param("id", ride.id()).update();
        assertAnswered("POST", RATING, rate(ride.rider().authorization(), ride.id(), "{\"stars\": 5}"), 201);

        jdbc.sql("UPDATE rating.rating_windows SET closes_at = now() - interval '1 millisecond' WHERE ride_id = :id")
                .param("id", ride.id()).update();

        assertProblem("POST", RATING, rate(ride.driver().authorization(), ride.id(), "{\"stars\": 5}"), 409,
                "RATING_NOT_OPEN");
    }

    @Test
    void aRedeliveredTripCompletedLeavesTheWindowAsItWas() {
        AssignedRide ride = completed();
        OffsetDateTime closes = closesAt(ride.id());
        UUID tripCompleted = jdbc.sql("""
                        SELECT event_id FROM platform.outbox WHERE aggregate_id = :id AND event_type = 'TripCompleted'
                        """).param("id", ride.id()).query(UUID.class).single();

        asWorker(() -> {
            events.replay(RatingWindows.NAME, tripCompleted);
            return null;
        });

        assertThat(closesAt(ride.id())).isEqualTo(closes);
    }

    @Test
    void onlyTheRiderAndTheDriverOfTheRideCanRateIt() {
        AssignedRide ride = completed();
        AssignedRide other = completed();

        assertProblem("POST", RATING, rate(users.create(UserRole.RIDER).authorization(), ride.id(), "{\"stars\": 1}"),
                409, "RATING_NOT_OPEN");
        assertProblem("POST", RATING, rate(other.driver().authorization(), ride.id(), "{\"stars\": 1}"), 409,
                "RATING_NOT_OPEN");
        assertProblem("POST", RATING, rate(other.rider().authorization(), ride.id(), "{\"stars\": 1}"), 409,
                "RATING_NOT_OPEN");
        assertProblem("POST", RATING, rate(ride.rider().authorization(), Ids.newId(), "{\"stars\": 1}"), 409,
                "RATING_NOT_OPEN");
        assertProblem("POST", RATING, rate(users.create(UserRole.OPS).authorization(), ride.id(), "{\"stars\": 1}"),
                403, "FORBIDDEN");
        assertProblem("POST", RATING, rate(null, ride.id(), "{\"stars\": 1}"), 401, "UNAUTHENTICATED");
        assertThat(jdbc.sql("SELECT count(*) FROM rating.ratings WHERE ride_id = :id").param("id", ride.id())
                .query(Long.class).single()).isZero();
    }

    @Test
    void starsAreOneToFiveAndCommentsAtMost500Characters() {
        AssignedRide ride = completed();
        String rider = ride.rider().authorization();

        for (String body : List.of("{\"stars\": 0}", "{\"stars\": 6}", "{}", "{\"stars\": 3, \"comment\": \"%s\"}"
                .formatted("a".repeat(501)))) {
            assertProblem("POST", RATING, rate(rider, ride.id(), body), 400, "VALIDATION_FAILED");
        }
        assertProblem("POST", RATING, rate(rider, ride.id(), "{\"stars\": 3}", null), 400,
                "IDEMPOTENCY_KEY_REQUIRED");

        assertAnswered("POST", RATING, rate(rider, ride.id(), "{\"stars\": 1, \"comment\": \"%s\""
                .formatted("a".repeat(500)) + "}"), 201);
        assertAnswered("POST", RATING, rate(ride.driver().authorization(), ride.id(), "{\"stars\": 5}"), 201);
    }

    @Test
    void theSummaryIsTheAverageOfTheLatest100RatingsRoundedToTwoPlaces() {
        UUID driver = Ids.newId();
        rateAs(Party.RIDER, Ids.newId(), driver, 1);
        for (int i = 0; i < 98; i++) {
            rateAs(Party.RIDER, Ids.newId(), driver, 5);
        }
        assertThat(api.summary(driver, Party.DRIVER)).isEqualTo(summary("4.96", 99));

        rateAs(Party.RIDER, Ids.newId(), driver, 4);
        assertThat(api.summary(driver, Party.DRIVER)).as("1 + 98 × 5 + 4 over 100").isEqualTo(summary("4.95", 100));

        rateAs(Party.RIDER, Ids.newId(), driver, 5);
        assertThat(api.summary(driver, Party.DRIVER)).as("the 1 dropped out").isEqualTo(summary("4.99", 100));
    }

    @Test
    void averagesAreRoundedHalfUp() {
        UUID twoThirds = Ids.newId();
        for (int stars : List.of(5, 5, 4)) {
            rateAs(Party.RIDER, Ids.newId(), twoThirds, stars);
        }
        UUID oneThird = Ids.newId();
        for (int stars : List.of(5, 4, 4)) {
            rateAs(Party.RIDER, Ids.newId(), oneThird, stars);
        }
        UUID half = Ids.newId();
        for (int stars : List.of(4, 3, 3, 3, 3, 3, 3, 3)) {
            rateAs(Party.RIDER, Ids.newId(), half, stars);
        }

        assertThat(api.summary(twoThirds, Party.DRIVER)).isEqualTo(summary("4.67", 3));
        assertThat(api.summary(oneThird, Party.DRIVER)).isEqualTo(summary("4.33", 3));
        assertThat(api.summary(half, Party.DRIVER)).as("3.125").isEqualTo(summary("3.13", 8));
    }

    @Test
    void aPersonsRatingsAsRiderAndAsDriverAreKeptApart() {
        UUID person = Ids.newId();
        rateAs(Party.RIDER, Ids.newId(), person, 5);
        rateAs(Party.DRIVER, Ids.newId(), person, 2);
        rateAs(Party.DRIVER, Ids.newId(), person, 3);

        assertThat(api.summary(person, Party.DRIVER)).isEqualTo(summary("5.00", 1));
        assertThat(api.summary(person, Party.RIDER)).isEqualTo(summary("2.50", 2));
    }

    @Test
    void ratingsOfOnePersonAtOnceAreAllCounted() throws Exception {
        UUID driver = Ids.newId();
        rateAs(Party.RIDER, Ids.newId(), driver, 1);
        List<UUID> rideIds = List.of(window(Ids.newId(), driver), window(Ids.newId(), driver));

        List<Future<?>> ratingsMade = new ArrayList<>();
        try (Connection blocker = Postgis.connection(); ExecutorService threads = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement(
                    "SELECT 1 FROM rating.summaries WHERE user_id = ? AND party = 'DRIVER' FOR UPDATE")) {
                lock.setObject(1, driver);
                lock.executeQuery();
            }
            for (int i = 0; i < rideIds.size(); i++) {
                UUID rideId = rideIds.get(i);
                int stars = 4 + i;
                ratingsMade.add(threads.submit(() -> asApi(() -> ratings.rate(rideId, riderOf(rideId), stars, null))));
            }
            Eventually.within(Duration.ofSeconds(10), () -> assertThat(sessionsWaitingForALock()).isEqualTo(2));
            blocker.rollback();
            for (Future<?> rating : ratingsMade) {
                rating.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(api.summary(driver, Party.DRIVER)).as("(1 + 4 + 5) / 3").isEqualTo(summary("3.33", 3));
    }

    @Test
    void twoRatingsOfOneSideAtOnceStoreOne() throws Exception {
        UUID driver = Ids.newId();
        UUID rideId = window(Ids.newId(), driver);
        UUID rider = riderOf(rideId);

        List<Future<String>> attempts = new ArrayList<>();
        try (Connection blocker = Postgis.connection(); ExecutorService threads = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try (PreparedStatement hold = blocker.prepareStatement("""
                    INSERT INTO rating.summaries (user_id, party, average, count, updated_at)
                    VALUES (?, 'DRIVER', 0, 0, now())""")) {
                hold.setObject(1, driver);
                hold.executeUpdate();
            }
            for (int stars : List.of(2, 5)) {
                attempts.add(threads.submit(() -> {
                    try {
                        asApi(() -> ratings.rate(rideId, rider, stars, null));
                        return "201";
                    } catch (ApiException e) {
                        return e.status().value() + " " + e.code();
                    }
                }));
            }
            Eventually.within(Duration.ofSeconds(10), () -> assertThat(sessionsWaitingForALock()).isEqualTo(2));
            blocker.rollback();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> attempt : attempts) {
                outcomes.add(attempt.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder("201", "409 ALREADY_RATED");
        }

        assertThat(api.summary(driver, Party.DRIVER).count()).isEqualTo(1);
    }

    /** Each ride gets a city of its own, so its offer can't go to an earlier ride's driver, now free again. */
    private TestCity newCity() {
        city = cities.create("MINI");
        prices.price(city.id(), "MINI");
        return city;
    }

    private AssignedRide completed() {
        AssignedRide ride = rides.assigned(newCity(), city.at(0.1, 0.1), city.at(0.1, 0.1));
        finish(ride);
        deliver(ride);
        return ride;
    }

    private void finish(AssignedRide ride) {
        asApi(() -> {
            drivers.arrive(ride.driver().id(), ride.id());
            drivers.start(ride.driver().id(), ride.id(), ride.pin());
            return drivers.complete(ride.driver().id(), ride.id());
        });
    }

    private void deliver(AssignedRide ride) {
        asWorker(() -> {
            events.deliverAll(ride.id());
            return null;
        });
    }

    private HttpResponse<String> rate(String authorization, UUID rideId, String body) {
        return rate(authorization, rideId, body, UUID.randomUUID().toString());
    }

    private HttpResponse<String> rate(String authorization, UUID rideId, String body, String key) {
        Map<String, String> headers = new HashMap<>();
        if (authorization != null) {
            headers.put("Authorization", authorization);
        }
        if (key != null) {
            headers.put(Idempotency.HEADER, key);
        }
        return postJson("/v1/rides/" + rideId + "/rating", headers, body);
    }

    /** A window of a ride that exists only here, between a new rider and this driver; answers the ride. */
    private UUID window(UUID riderId, UUID driverId) {
        UUID rideId = Ids.newId();
        transactions.run(() -> repository.openWindow(rideId, riderId, driverId, Instant.now(), Duration.ofDays(7)));
        return rideId;
    }

    private UUID riderOf(UUID rideId) {
        return jdbc.sql("SELECT rider_id FROM rating.rating_windows WHERE ride_id = :id").param("id", rideId)
                .query(UUID.class).single();
    }

    /** {@code rater} rates {@code ratee} as {@code side} on a ride of their own. */
    private void rateAs(Party side, UUID rater, UUID ratee, int stars) {
        UUID rideId = side == Party.RIDER ? window(rater, ratee) : window(ratee, rater);
        asApi(() -> ratings.rate(rideId, rater, stars, null));
    }

    private OffsetDateTime closesAt(UUID rideId) {
        return jdbc.sql("SELECT closes_at FROM rating.rating_windows WHERE ride_id = :id").param("id", rideId)
                .query(OffsetDateTime.class).single();
    }

    private long sessionsWaitingForALock() {
        return jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                        + " AND datname = current_database()")
                .query(Long.class)
                .single();
    }

    private static RatingSummary summary(String average, int count) {
        return new RatingSummary(new BigDecimal(average), count);
    }
}
